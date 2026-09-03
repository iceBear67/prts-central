package io.ib67.prts.project;

import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.job.entity.PendingJob;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.WorkerService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.function.LongSupplier;

@ApplicationScoped
public class JobService {
    private static final Logger LOG = Logger.getLogger(JobService.class);

    @Inject
    ProjectService projectService;
    @Inject
    WorkerService workerService;
    @Inject
    JobSpecOverridePermissions overridePermissions;

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
     * Creates a job from a template, applying the override fields the caller is permitted to set,
     * then schedules it. Reaching the project is the endpoint's business; enforced here is what the
     * spec may reach — one permission per override field, the template's own project, and the
     * volume rule.
     */
    public Job createFromTemplate(
            UUID projectId,
            UUID templateId,
            @Nullable JobSpecOverride override,
            @Nullable String resourceClass) {
        return dispatch(QuarkusTransaction.requiringNew()
                .call(() -> prepareFromTemplate(projectId, templateId, override, resourceClass)));
    }

    /**
     * Hands a prepared job to the scheduler. A job that cannot be scheduled at all is failed —
     * being merely unplaceable for now is not an error, the scheduler queues it.
     */
    private Job dispatch(PreparedJob prepared) {
        var jobId = prepared.job().getId();
        try {
            workerService.schedule(jobId, prepared.resourceClass(), prepared.spec());
        } catch (RuntimeException e) {
            QuarkusTransaction.requiringNew().run(() -> {
                Job.<Job>findByIdOptional(jobId).ifPresent(job -> job.transitionTo(JobState.FAILED));
                JobLock.releaseBy(jobId);
            });
            throw e;
        }
        return prepared.job();
    }

    private PreparedJob prepareFromTemplate(
            UUID projectId,
            UUID templateId,
            @Nullable JobSpecOverride override,
            @Nullable String resourceClassName) {
        var project = projectService.findById(projectId).orElseThrow(NotFoundException::new);
        // Scoped, not merely fetched: a template of another project must not be reachable from here.
        var template = JobSpecTemplate.findVisibleFetched(projectId, templateId)
                .orElseThrow(() -> new NotFoundException("no such template: " + templateId));
        if (template.getSpec() == null) {
            throw new BadRequestException("template has no job spec");
        }
        var spec = override == null
                ? template.getSpec()
                : override.applyTo(template.getSpec(), overridePermissions);
        spec.requireVolumesIn(projectId);
        var resourceClass = resolveResourceClass(projectId, resourceClassName, template.getResourceClass());
        var job = Job.builder()
                .project(project)
                .spec(spec)
                .resourceClass(resourceClass)
                .templateId(templateId)
                .createOverride(override)
                .build();
        job.persist();
        return new PreparedJob(job, spec, resourceClass);
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
        // Drops it from the queue when it was never handed to a worker.
        PendingJob.deleteByJob(jobId);
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

    /**
     * The class the job runs under: what the caller asked for, else the template's. Only a request
     * that actually deviates from the template is gated, so replaying a stored request takes no more
     * permission than the create it replays.
     */
    private ResourceClass resolveResourceClass(
            UUID projectId, @Nullable String requestedName, @Nullable ResourceClass templateClass) {
        if (requestedName == null || requestedName.equals(nameOf(templateClass))) {
            if (templateClass == null || templateClass.getName() == null) {
                throw new BadRequestException("resource class is required");
            }
            return requireVisible(projectId, templateClass);
        }
        overridePermissions.resourceClass(requestedName);
        return ResourceClass.findVisible(projectId, requestedName)
                .orElseThrow(() -> new NotFoundException("no such resource class: " + requestedName));
    }

    private static String nameOf(@Nullable ResourceClass klass) {
        return klass == null ? null : klass.getName();
    }

    /**
     * A template may name a class of its own project or a global one; anything else would run the
     * spec under another project's definition. {@link ResourceClass#findVisible} already cannot
     * return one, so this only guards what the template points at.
     */
    private static ResourceClass requireVisible(UUID projectId, ResourceClass klass) {
        if (klass.isGlobal() || klass.getProjectId().equals(projectId)) {
            return klass;
        }
        throw new BadRequestException(
                "template names a resource class of another project: " + klass.getName());
    }

    private record PreparedJob(Job job, JobSpec spec, ResourceClass resourceClass) {
    }

    /** @param worker the worker that still has to be told, or {@code null} if never dispatched. */
    private record CancelledJob(Job job, @Nullable UUID worker) {
    }

    /** Locked like {@link #prepareCancel}: the terminal-state guard below is a check-then-write. */
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

    /** No caller yet, on purpose: a real delete releases the job's logs, artifacts and their S3 objects in code. */
    @Transactional
    public boolean delete(UUID jobId) {
        return Job.deleteById(jobId);
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

    public List<Artifact> listArtifacts(UUID projectId, UUID jobId) {
        requireInProject(projectId, jobId);
        return Artifact.listByJob(jobId);
    }

    /** Same rule as {@link #requireIn}: found by id, then kept only if it belongs to the project. */
    public Optional<Artifact> findArtifact(UUID projectId, UUID artifactId) {
        return Artifact.<Artifact>findByIdOptional(artifactId)
                .filter(artifact -> artifact.getJob().getProject().getId().equals(projectId));
    }

    @Transactional
    public void assertCanStoreArtifact(
            UUID jobId,
            UUID workerId,
            long additionalBytes,
            LongSupplier reservedBytes,
            long maxJobSize,
            Runnable reserve) {
        lockAssignedOpen(jobId, workerId);
        var used = Artifact.listByJob(jobId).stream().mapToLong(Artifact::getSizeBytes).sum();
        var reserved = reservedBytes.getAsLong();
        if (used > maxJobSize
                || reserved > maxJobSize - used
                || additionalBytes > maxJobSize - used - reserved) {
            throw new IllegalStateException(
                    "job artifact quota exceeded: " + (used + reserved + additionalBytes) + " > " + maxJobSize);
        }
        reserve.run();
    }

    @Transactional
    public Artifact addArtifact(UUID jobId, UUID workerId, String name, String objectKey, long sizeBytes) {
        var job = lockAssignedOpen(jobId, workerId);
        var existing = Artifact.<Artifact>find("objectKey", objectKey).firstResult();
        if (existing != null) {
            return existing;
        }
        var artifact = Artifact.builder()
                .job(job)
                .name(name)
                .objectKey(objectKey)
                .sizeBytes(sizeBytes)
                .build();
        artifact.persist();
        return artifact;
    }

    @Transactional
    public boolean deleteArtifact(UUID artifactId) {
        return Artifact.deleteById(artifactId);
    }

    public Job requireOpen(UUID jobId) {
        var job = require(jobId);
        if (job.isCompleted()) {
            throw new IllegalStateException("job already completed: " + jobId);
        }
        return job;
    }

    private Job lockAssignedOpen(UUID jobId, UUID workerId) {
        var job = Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE);
        if (job == null) {
            throw new NoSuchElementException("no such job: " + jobId);
        }
        if (job.isCompleted()) {
            throw new IllegalStateException("job already completed: " + jobId);
        }
        if (!workerId.equals(job.getWorker())) {
            throw new IllegalStateException("job not assigned to this worker: " + jobId);
        }
        return job;
    }
}
