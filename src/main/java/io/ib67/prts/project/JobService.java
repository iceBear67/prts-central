package io.ib67.prts.project;

import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.worker.WorkerService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
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

/**
 * A job's own state and what is read off it. Making one is {@link JobLauncher}'s business, and its
 * artifacts {@link ArtifactUploadService}'s; what stays here is the state machine every writer goes
 * through — the worker reporting, the requester cancelling, the launcher giving up — and the logs.
 */
@ApplicationScoped
public class JobService {
    private static final Logger LOG = Logger.getLogger(JobService.class);

    @Inject
    ProjectService projectService;
    @Inject
    WorkerService workerService;

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
     * Jobs carry their own id, so a lookup is by id alone and the project from the request only
     * decides whether the caller may see the result — reaching a job through the wrong project is a
     * miss, not a different job.
     */
    private static Job requireIn(UUID projectId, UUID jobId, @Nullable Job job) {
        if (job == null || !job.getProject().getId().equals(projectId)) {
            throw new NotFoundException("no such job in project " + projectId + ": " + jobId);
        }
        return job;
    }

    public List<Job> listByProject(UUID projectId) {
        projectService.require(projectId);
        return Job.listByProject(projectId);
    }

    /**
     * Marks the job {@link JobState#CANCELLED} and tells the worker running it to stop. Our state is
     * authoritative: a late report from the worker is ignored by {@link #applyState(UUID, JobState)}.
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
        persistLog(job, "state", previous + " -> " + JobState.CANCELLED, false);
        return new CancelledJob(job, job.getWorker());
    }

    /** Best effort: a missing log line must never mask the cancellation itself. */
    private void logCancelOutcome(UUID jobId, String message) {
        try {
            QuarkusTransaction.requiringNew().run(() -> Job.<Job>findByIdOptional(jobId)
                    .ifPresent(job -> persistLog(job, "cancel", message, false)));
        } catch (RuntimeException e) {
            LOG.errorf(e, "failed to log the cancel outcome for job %s", jobId);
        }
    }

    /** @param worker the worker that still has to be told, or {@code null} if never dispatched. */
    private record CancelledJob(Job job, @Nullable UUID worker) {
        private CancelledJob {
            Objects.requireNonNull(job, "job");
        }
    }

    /**
     * Moves the job to {@code state} unless it is already terminal, releasing its {@link JobLock} on a
     * terminal one. Locked like {@link #prepareCancel}: the terminal-state guard is a check-then-write.
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
        }
        persistLog(job, "state", previous + " -> " + state, state == JobState.FAILED);
        return found;
    }

    /**
     * Deletes a job no worker took: it never ran, so it has no logs, no artifacts and nothing worth
     * keeping. The lock is already released by the scheduler when it reports a job unplaceable; this
     * covers the paths where it is not.
     *
     * <p>"No worker took it" is checked, not assumed: a job already gone is left alone, and one that
     * reached a worker is refused. {@link JobState#PENDING} does not say so by itself — the scheduler
     * sets {@code worker} on hand-over and the state only moves when the worker reports back, so a
     * job with a worker may have a container starting behind it. Deleting that would strand the
     * container, drop the row its reports land on, and free the lock for a job to run beside it.
     */
    public void discard(UUID jobId) {
        QuarkusTransaction.requiringNew().run(() -> {
            var job = Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE);
            if (job == null) {
                return;
            }
            if (job.getState() != JobState.PENDING || job.getWorker() != null) {
                // A wiring bug, not a race: today's only caller reaches this having been told the job
                // was never offered to anyone. Loud, so the next caller that is wrong finds out here.
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
