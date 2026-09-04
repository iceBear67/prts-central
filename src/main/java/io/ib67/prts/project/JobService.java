package io.ib67.prts.project;

import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.agent.job.JobSpecOverrideAuthorizer;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.secret.SecretService;
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
    @Inject
    SecretService secretService;

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
    public CreatedJob createFromTemplate(
            UUID projectId,
            UUID templateId,
            @Nullable JobSpecOverride override,
            @Nullable String resourceClass) {
        return dispatch(
                QuarkusTransaction.requiringNew().call(
                        () -> prepare(projectId, templateId, override, resourceClass, overridePermissions)),
                OnRefusal.FAIL);
    }

    /**
     * Runs every check {@link #createFromTemplate} runs, persists nothing, and answers with the
     * resource class the create resolves to. What lets a request be cleared while its requester is
     * still on the line and submitted later — see {@link io.ib67.prts.pending.PendingJobService}.
     */
    public String authorizeCreate(
            UUID projectId,
            UUID templateId,
            @Nullable JobSpecOverride override,
            @Nullable String resourceClass) {
        return QuarkusTransaction.requiringNew()
                .call(() -> resolve(projectId, templateId, override, resourceClass, overridePermissions))
                .resourceClass()
                .getName();
    }

    /**
     * Replays a request {@link #authorizeCreate} already cleared, from a thread that carries neither
     * the requester nor their request. Only the per-field override rules are taken as settled;
     * everything else — the template's project, the volume rule, the class the spec may name — is
     * checked again against the state of now.
     */
    public CreatedJob createPreAuthorized(
            UUID projectId,
            UUID templateId,
            @Nullable JobSpecOverride override,
            @Nullable String resourceClass) {
        return dispatch(
                QuarkusTransaction.requiringNew().call(
                        () -> prepare(projectId, templateId, override, resourceClass, PRE_AUTHORIZED)),
                OnRefusal.DISCARD);
    }

    /**
     * Clears every override field without asking. Sound only for a request already cleared by
     * {@link #authorizeCreate}, which is why nothing outside this class can reach one.
     */
    private static final JobSpecOverrideAuthorizer PRE_AUTHORIZED = new JobSpecOverrideAuthorizer() {
        @Override public String image(String value) { return value; }
        @Override public Map<String, String> environment(Map<String, String> value) { return value; }
        @Override public Map<String, String> labels(Map<String, String> value) { return value; }
        @Override public List<String> command(List<String> value) { return value; }
        @Override public Map<UUID, JobSpec.VolumeSpec> volumes(Map<UUID, JobSpec.VolumeSpec> value) { return value; }
        @Override public long timeout(long value) { return value; }
        @Override public String lock(String value) { return value; }
        @Override public String resourceClass(String name) { return name; }
    };

    /**
     * @param scheduled {@code false} when no worker could take the job. What the row looks like
     *                  afterwards is {@link OnRefusal}'s business; saying so is the caller's.
     */
    public record CreatedJob(Job job, boolean scheduled) {
    }

    /** What an unplaceable job leaves behind. */
    private enum OnRefusal {
        /** A {@link JobState#FAILED} row: the caller asked for this job and gets to see it refused. */
        FAIL,
        /** Nothing at all: the attempt is one of many, and each would otherwise leave a failure. */
        DISCARD
    }

    /**
     * Hands a prepared job to the scheduler and undoes it if that does not work out. Being
     * unplaceable is one such failure but not an error here: it is reported, not thrown.
     */
    private CreatedJob dispatch(PreparedJob prepared, OnRefusal onRefusal) {
        var jobId = prepared.job().getId();
        boolean scheduled;
        try {
            scheduled = workerService.schedule(jobId, prepared.resourceClass(), prepared.spec());
        } catch (RuntimeException e) {
            // Failed rather than discarded whatever the policy: the offer may have reached a worker
            // that started a container, and a job that may have run has to remain visible.
            failAndRelease(jobId);
            throw e;
        }
        if (!scheduled) {
            if (onRefusal == OnRefusal.DISCARD) {
                discard(jobId);
            } else {
                failAndRelease(jobId);
            }
        }
        return new CreatedJob(prepared.job(), scheduled);
    }

    /** The job never ran, so nothing will report on it and its {@link JobLock} would be held forever. */
    private void failAndRelease(UUID jobId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Job.<Job>findByIdOptional(jobId).ifPresent(job -> job.transitionTo(JobState.FAILED));
            JobLock.releaseBy(jobId);
        });
    }

    /** No worker ever saw it, so it has no logs, no artifacts and nothing worth keeping. */
    private void discard(UUID jobId) {
        QuarkusTransaction.requiringNew().run(() -> {
            JobLock.releaseBy(jobId);
            Job.deleteById(jobId);
        });
    }

    private PreparedJob prepare(
            UUID projectId,
            UUID templateId,
            @Nullable JobSpecOverride override,
            @Nullable String resourceClassName,
            JobSpecOverrideAuthorizer authorizer) {
        var resolved = resolve(projectId, templateId, override, resourceClassName, authorizer);
        var job = Job.builder()
                .project(resolved.project())
                .spec(resolved.spec())
                .resourceClass(resolved.resourceClass())
                .templateId(templateId)
                .createOverride(override)
                .build();
        job.persist();
        // Only the copy handed to the scheduler carries the secrets: the entity keeps the spec
        // without them, so neither the row nor a view read back off it can hold plaintext even if
        // the @JsonIgnore that already drops them were to go. Resolved per attempt, so a replayed
        // request picks up the project's secrets as they are now and never carries any itself.
        return new PreparedJob(
                job, resolved.spec().withSecret(secretService.resolve(projectId)), resolved.resourceClass());
    }

    /** Everything a create needs its caller cleared for, with nothing persisted yet. */
    private ResolvedCreate resolve(
            UUID projectId,
            UUID templateId,
            @Nullable JobSpecOverride override,
            @Nullable String resourceClassName,
            JobSpecOverrideAuthorizer authorizer) {
        var project = projectService.findById(projectId).orElseThrow(NotFoundException::new);
        // Scoped, not merely fetched: a template of another project must not be reachable from here.
        var template = JobSpecTemplate.findVisibleFetched(projectId, templateId)
                .orElseThrow(() -> new NotFoundException("no such template: " + templateId));
        if (template.getSpec() == null) {
            throw new BadRequestException("template has no job spec");
        }
        var spec = override == null
                ? template.getSpec()
                : override.applyTo(template.getSpec(), authorizer);
        spec.requireVolumesIn(projectId);
        return new ResolvedCreate(
                project,
                spec,
                resolveResourceClass(projectId, resourceClassName, template.getResourceClass(), authorizer));
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

    /**
     * The class the job runs under: what the caller asked for, else the template's. Only a request
     * that actually deviates from the template is gated, so replaying a stored request takes no more
     * permission than the create it replays.
     */
    private ResourceClass resolveResourceClass(
            UUID projectId,
            @Nullable String requestedName,
            @Nullable ResourceClass templateClass,
            JobSpecOverrideAuthorizer authorizer) {
        if (requestedName == null || requestedName.equals(nameOf(templateClass))) {
            if (templateClass == null || templateClass.getName() == null) {
                throw new BadRequestException("resource class is required");
            }
            return requireVisible(projectId, templateClass);
        }
        authorizer.resourceClass(requestedName);
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

    /** @param spec the merged spec <em>with</em> the project's secrets — never the persisted one. */
    private record PreparedJob(Job job, JobSpec spec, ResourceClass resourceClass) {
    }

    /** @param spec the merged spec <em>without</em> secrets: nothing is running yet. */
    private record ResolvedCreate(Project project, JobSpec spec, ResourceClass resourceClass) {
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
