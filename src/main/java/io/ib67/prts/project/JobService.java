package io.ib67.prts.project;

import io.ib67.prts.Perms;
import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.job.entity.PendingJob;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.dto.CreateJobRequest;
import io.ib67.prts.dto.JobView;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
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
    UserContext userContext;
    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;
    @Inject
    WorkerService workerService;
    @Inject
    JobSpecOverridePermissions overridePermissions;

    public Optional<Job> findById(UUID id) {
        return Job.findByIdOptional(id);
    }

    public Job require(UUID id) {
        return Job.<Job>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such job: " + id));
    }

    public List<Job> listByProject(UUID projectId) {
        projectService.require(projectId);
        return Job.listByProject(projectId);
    }

    @Transactional
    public Job create(UUID projectId, UUID worker) {
        var job = Job.builder()
                .project(projectService.require(projectId))
                .worker(worker)
                .build();
        job.persist();
        return job;
    }

    /**
     * Creates a job from a template, applying permitted spec/resource-class overrides, then
     * schedules it. Project membership is required; each override field has its own permission.
     */
    public JobView createFromTemplate(UUID projectId, CreateJobRequest request) {
        return dispatch(QuarkusTransaction.requiringNew().call(() -> prepareFromTemplate(projectId, request)));
    }

    /**
     * Runs the spec a job was created with again, as a new job in the same project. The caller needs
     * the same project membership and volume access as an original create.
     */
    public JobView rerun(UUID jobId) {
        return dispatch(QuarkusTransaction.requiringNew().call(() -> prepareRerun(jobId)));
    }

    /**
     * Hands a prepared job to the scheduler. A job that cannot be scheduled at all is failed —
     * being merely unplaceable for now is not an error, the scheduler queues it.
     */
    private JobView dispatch(PreparedJob prepared) {
        var jobId = prepared.view().id();
        try {
            workerService.schedule(jobId, prepared.resourceClass(), prepared.spec());
        } catch (RuntimeException e) {
            QuarkusTransaction.requiringNew().run(() -> {
                Job.<Job>findByIdOptional(jobId).ifPresent(job -> job.transitionTo(JobState.FAILED));
                JobLock.releaseBy(jobId);
            });
            throw e;
        }
        return prepared.view();
    }

    private PreparedJob prepareFromTemplate(UUID projectId, CreateJobRequest request) {
        var user = requireUser();
        var project = projectService.findById(projectId).orElseThrow(NotFoundException::new);
        requireProjectMember(user, projectId);
        if (request == null || request.templateId() == null) {
            throw new BadRequestException("templateId is required");
        }
        var template = JobSpecTemplate.findByIdFetched(request.templateId())
                .orElseThrow(() -> new NotFoundException("no such template: " + request.templateId()));
        if (template.getSpec() == null) {
            throw new BadRequestException("template has no job spec");
        }
        var override = request.override();
        var spec = override == null
                ? template.getSpec()
                : override.applyTo(template.getSpec(), overridePermissions);
        spec = spec.withPrompt(request.prompt());
        spec.requireVolumeAccess(volumeProject ->
                userService.hasAtLeast(user.getId(), volumeProject, ProjectRole.MEMBER));
        var resourceClass = resolveResourceClass(
                request.resourceClass() == null ? null : overridePermissions.resourceClass(request.resourceClass()),
                template.getResourceClass());
        var job = Job.builder().project(project).spec(spec).resourceClass(resourceClass).build();
        job.persist();
        return new PreparedJob(JobView.of(job, List.of()), spec, resourceClass);
    }

    private PreparedJob prepareRerun(UUID jobId) {
        var user = requireUser();
        var source = Job.findByIdFetched(jobId).orElseThrow(NotFoundException::new);
        var project = source.getProject();
        requireProjectMember(user, project.getId());
        var spec = source.getSpec();
        if (spec == null) {
            throw new BadRequestException("job has no spec to rerun");
        }
        var resourceClass = source.getResourceClass();
        if (resourceClass == null || resourceClass.getName() == null) {
            throw new BadRequestException("job has no resource class to rerun with");
        }
        spec.requireVolumeAccess(volumeProject ->
                userService.hasAtLeast(user.getId(), volumeProject, ProjectRole.MEMBER));
        var job = Job.builder().project(project).spec(spec).resourceClass(resourceClass).build();
        job.persist();
        return new PreparedJob(JobView.of(job, List.of()), spec, resourceClass);
    }

    /**
     * Marks the job {@link JobState#CANCELLED} and tells the worker running it to stop. Our state is
     * authoritative: a late report from the worker is ignored by {@link #applyState(UUID, JobState)}.
     */
    public JobView cancel(UUID jobId) {
        var cancelled = QuarkusTransaction.requiringNew().call(() -> prepareCancel(jobId));
        var worker = cancelled.worker();
        if (worker == null) {
            return cancelled.view();
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
        return cancelled.view();
    }

    private CancelledJob prepareCancel(UUID jobId) {
        var user = requireUser();
        var job = Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE);
        if (job == null) {
            throw new NotFoundException("no such job: " + jobId);
        }
        requireProjectMember(user, job.getProject().getId());
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
        return new CancelledJob(JobView.of(job, Artifact.listByJob(jobId)), job.getWorker());
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

    private User requireUser() {
        var user = userContext.get();
        if (user == null) {
            throw new UnauthorizedException();
        }
        return user;
    }

    private void requireProjectMember(User user, UUID projectId) {
        if (permissionService.has(user.getId(), Perms.ADMIN_OF_ALL)) {
            return;
        }
        if (!userService.hasAtLeast(user.getId(), projectId, ProjectRole.MEMBER)) {
            throw new ForbiddenException("missing project permission");
        }
    }

    private ResourceClass resolveResourceClass(String requestedName, ResourceClass templateClass) {
        if (requestedName != null) {
            var found = ResourceClass.<ResourceClass>findById(requestedName);
            if (found == null) {
                throw new NotFoundException("no such resource class: " + requestedName);
            }
            return found;
        }
        if (templateClass == null || templateClass.getName() == null) {
            throw new BadRequestException("resource class is required");
        }
        return templateClass;
    }

    private record PreparedJob(JobView view, JobSpec spec, ResourceClass resourceClass) {
    }

    /** @param worker the worker that still has to be told, or {@code null} if never dispatched. */
    private record CancelledJob(JobView view, @Nullable UUID worker) {
    }

    @Transactional
    public Job assignWorker(UUID jobId, UUID worker) {
        var job = requireOpen(jobId);
        job.setWorker(worker);
        return job;
    }

    @Transactional
    public Optional<Job> applyState(UUID jobId, JobState state) {
        Objects.requireNonNull(state, "jobState");
        var found = Job.<Job>findByIdOptional(jobId);
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

    @Transactional
    public boolean delete(UUID jobId) {
        return Job.deleteById(jobId);
    }

    public List<JobLog> listLogs(UUID jobId) {
        require(jobId);
        return JobLog.listByJob(jobId);
    }

    public List<JobLog> listLogs(UUID jobId, int offset, int length) {
        require(jobId);
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

    public List<Artifact> listArtifacts(UUID jobId) {
        require(jobId);
        return Artifact.listByJob(jobId);
    }

    public Optional<Artifact> findArtifact(UUID artifactId) {
        return Artifact.findByIdOptional(artifactId);
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
