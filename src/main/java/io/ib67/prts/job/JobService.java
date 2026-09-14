package io.ib67.prts.job;

import io.ib67.prts.agent.acp.AgentService;
import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.job.JobView;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobLog;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.notification.NotificationService;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.storage.ArtifactService;
import io.ib67.prts.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages job state transitions, cancellations, and logs.
 */
@ApplicationScoped
public class JobService {
    private static final Logger LOG = Logger.getLogger(JobService.class);

    @Inject
    ProjectService projectService;
    @Inject
    WorkerService workerService;
    @Inject
    ArtifactService artifactService;
    @Inject
    NotificationService notificationService;
    @Inject
    JobAccess jobAccess;
    @Inject
    AgentService agentService;
    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

    public Job require(UUID id) {
        return Job.<Job>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such job: " + id));
    }

    public Optional<Job> findInProject(UUID projectId, UUID jobId) {
        return Job.<Job>findByIdOptional(jobId).filter(job -> job.getProject().getId().equals(projectId));
    }

    public Job requireInProject(UUID projectId, UUID jobId) {
        return requireIn(projectId, jobId, Job.findById(jobId));
    }

    /**
     * Validates that the job exists and belongs to the specified project.
     */
    private static Job requireIn(UUID projectId, UUID jobId, @Nullable Job job) {
        if (job == null || !job.getProject().getId().equals(projectId)) {
            throw new NotFoundException("no such job in project " + projectId + ": " + jobId);
        }
        return job;
    }

    /** Lists visible jobs in the project up to the given limit. */
    public List<Job> listVisible(UUID projectId, int limit) {
        projectService.require(projectId);
        return Job.listVisibleByProject(projectId, limit);
    }

    /** Lists visible jobs a task scoped, up to the given limit. */
    public List<Job> listVisibleInTask(UUID projectId, UUID taskId, int limit) {
        Task.findInProject(projectId, taskId)
                .orElseThrow(() -> new NotFoundException("no such task in project " + projectId + ": " + taskId));
        return Job.listVisibleByTask(taskId, limit);
    }

    /** Builds a JobView for a single job, including the creation payload if the caller has job creation permissions. */
    public JobView viewOf(UUID projectId, Job job) {
        return viewOf(List.of(job), jobAccess.mayCreate(projectId)).getFirst();
    }

    /** Builds JobViews for a list of project jobs, resolving requesters and artifacts in bulk. */
    public List<JobView> viewOf(UUID projectId, List<Job> jobs) {
        return viewOf(jobs, jobAccess.mayCreate(projectId));
    }

    /**
     * Builds cross-project JobViews without job creation payloads.
     */
    public List<JobView> viewOf(List<Job> jobs) {
        return viewOf(jobs, false);
    }

    private List<JobView> viewOf(List<Job> jobs, boolean withRequest) {
        var users = User.mapByIds(jobs.stream().map(Job::getRequestedBy).distinct().toList());
        var artifacts = Artifact.listByJobs(jobs.stream().map(Job::getId).toList()).stream()
                .collect(Collectors.groupingBy(artifact -> artifact.getJob().getId()));
        return jobs.stream()
                .map(job -> new JobView(
                        job.getId(),
                        job.getProject().getId(),
                        job.getCreatedAt(),
                        job.getCompletedAt(),
                        job.getState(),
                        job.getWorker(),
                        UserInfo.of(job.getRequestedBy(), users.get(job.getRequestedBy())),
                        job.getResourceClass().getName(),
                        JobView.SpecView.of(job.getSpec()),
                        artifacts.getOrDefault(job.getId(), List.of()).stream()
                                .map(JobView.ArtifactView::of)
                                .toList(),
                        withRequest ? createRequestOf(job) : null))
                .toList();
    }

    @Nullable
    private static CreateJobRequest createRequestOf(Job job) {
        var request = job.toRequest();
        return request == null ? null : CreateJobRequest.of(request);
    }

    /**
     * Cancels a job, transitions its state to {@link JobState#CANCELLED}, and signals the assigned worker.
     */
    public Job cancel(UUID projectId, UUID jobId) {
        var cancelled = QuarkusTransaction.requiringNew().call(() -> prepareCancel(projectId, jobId));
        var worker = cancelled.worker();
        if (worker == null) {
            return cancelled.job();
        }
        var notified = false;
        try {
            notified = workerService.cancelJob(worker, jobId);
        } catch (RuntimeException e) {
            LOG.errorf(e, "job %s was cancelled but worker %s could not be told", jobId, worker);
        }
        logCancelOutcome(jobId, notified
                ? "worker " + worker + " told to stop the job"
                : "worker " + worker + " could not be reached");
        return cancelled.job();
    }

    private CancelledJob prepareCancel(UUID projectId, UUID jobId) {
        var job = requireIn(projectId, jobId, Job.findById(jobId, LockModeType.PESSIMISTIC_WRITE));
        if (job.isCompleted()) {
            throw new ClientErrorException(
                    "job already " + job.getState() + ": " + jobId, Response.Status.CONFLICT);
        }
        var previous = job.getState();
        job.transitionTo(JobState.CANCELLED);
        JobLock.releaseBy(jobId);
        closeAgentAfterCommit(jobId);
        persistLog(job, "state", previous + " -> " + JobState.CANCELLED, false);
        return new CancelledJob(job, job.getWorker());
    }

    private void logCancelOutcome(UUID jobId, String message) {
        try {
            QuarkusTransaction.requiringNew().run(() -> Job.<Job>findByIdOptional(jobId)
                    .ifPresent(job -> persistLog(job, "cancel", message, false)));
        } catch (RuntimeException e) {
            LOG.errorf(e, "failed to log the cancel outcome for job %s", jobId);
        }
    }

    private record CancelledJob(Job job, @Nullable UUID worker) {
        private CancelledJob {
            Objects.requireNonNull(job, "job");
        }
    }

    /**
     * Applies a state transition to a job if not already completed, releasing any held lock on terminal states.
     */
    @Transactional
    public Optional<Job> applyState(UUID jobId, JobState state) {
        Objects.requireNonNull(state, "jobState");
        var found = Optional.ofNullable(Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var job = found.get();
        if (job.isCompleted() || job.getState() == state) {
            return found;
        }
        var previous = job.getState();
        job.transitionTo(state);
        if (state.isTerminal()) {
            JobLock.releaseBy(jobId);
            closeAgentAfterCommit(jobId);
        }
        persistLog(job, "state", previous + " -> " + state, state == JobState.FAILED);
        if (state == JobState.FAILED) {
            reportFailure(job);
        }
        return found;
    }

    /**
     * Closes the ACP channel post-commit to avoid network I/O inside an active transaction.
     */
    private void closeAgentAfterCommit(UUID jobId) {
        transactionRegistry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) {
                    return;
                }
                try {
                    agentService.onJobClosed(jobId);
                } catch (RuntimeException e) {
                    LOG.errorf(e, "cannot close the agent channel of job %s", jobId);
                }
            }
        });
    }

    /**
     * Tells whoever asked for the job that it failed, however it got there — a worker's own report, the
     * disconnect that orphaned it, or a scheduler that threw.
     *
     * <p>Uses {@link NotificationService#notifyIfPresent} rather than {@code notify}: the requester may
     * have been deleted since, and a miss thrown from this transaction would roll back the very
     * transition being reported.
     */
    private void reportFailure(Job job) {
        var spec = job.getSpec();
        var description = spec == null ? "" : spec.description();
        notificationService.notifyIfPresent(
                job.getRequestedBy(),
                "prts",
                "Job failed in " + job.getProject().getName(),
                description.isEmpty()
                        ? "Job " + job.getId() + " failed."
                        : "Job " + job.getId() + " (" + description + ") failed.");
    }

    /**
     * Cancels active jobs and dispatches interrupt requests to their assigned workers.
     *
     * <p>Shared by project deletion, archiving, and task closure. Operations are executed on a
     * best-effort basis.
     *
     * @param jobs List of open job descriptors to terminate.
     */
    public void stopOpen(List<Job.Open> jobs, String reason) {
        for (var job : jobs) {
            try {
                applyState(job.id(), JobState.CANCELLED);
            } catch (RuntimeException e) {
                LOG.errorf(e, "cannot cancel job %s", job.id());
            }
            if (job.worker() != null) {
                try {
                    if (!workerService.interrupt(job.worker(), job.id(), reason)) {
                        LOG.warnf("worker %s is not connected: job %s may still be running there",
                                job.worker(), job.id());
                    }
                } catch (RuntimeException e) {
                    LOG.errorf(e, "cannot interrupt job %s on worker %s", job.id(), job.worker());
                }
            }
            artifactService.discardPendingOf(job.id());
        }
    }

    /**
     * Deletes an unassigned pending job that could not be scheduled.
     */
    public void discard(UUID jobId) {
        QuarkusTransaction.requiringNew().run(() -> {
            var job = Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE);
            if (job == null) {
                return;
            }
            if (job.getState() != JobState.PENDING || job.getWorker() != null) {
                throw new IllegalStateException(
                        "refusing to discard job " + jobId + ": " + job.getState()
                                + ", worker " + job.getWorker());
            }
            JobLock.releaseBy(jobId);
            job.delete();
        });
    }

    public List<JobLog> listLogs(UUID projectId, UUID jobId, int offset, int length) {
        requireInProject(projectId, jobId);
        return JobLog.listByJob(jobId, offset, length);
    }

    @Transactional
    public JobLog appendLog(UUID jobId, String topic, String message, Boolean error) {
        return persistLog(requireOpen(jobId), topic, message, error);
    }

    private JobLog persistLog(Job job, String topic, String message, Boolean error) {
        var log = JobLog.builder()
                .job(job)
                .topic(topic)
                .message(message)
                .error(Boolean.TRUE.equals(error))
                .build();
        log.persist();
        return log;
    }

    public Job requireOpen(UUID jobId) {
        var job = require(jobId);
        if (job.isCompleted()) {
            throw new IllegalStateException("job already completed: " + jobId);
        }
        return job;
    }
}
