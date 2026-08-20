package io.ib67.prts.project;

import io.ib67.prts.Perms;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.JobSpecTemplate;
import io.ib67.prts.agent.worker.ResourceClass;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.util.*;
import java.util.function.LongSupplier;

@ApplicationScoped
public class JobService {

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
        var prepared = QuarkusTransaction.requiringNew().call(() -> prepareFromTemplate(projectId, request));
        try {
            workerService.schedule(prepared.resourceClass(), prepared.spec());
        } catch (RuntimeException e) {
            QuarkusTransaction.requiringNew().run(() ->
                    Job.<Job>findByIdOptional(prepared.view().id())
                            .ifPresent(job -> job.transitionTo(JobState.FAILED)));
            throw e;
        }
        return prepared.view();
    }

    private PreparedJob prepareFromTemplate(UUID projectId, CreateJobRequest request) {
        var user = requireUser();
        var admin = permissionService.has(user.getId(), Perms.ADMIN_OF_ALL);
        var project = projectService.findById(projectId).orElseThrow(NotFoundException::new);
        if (!admin && !userService.hasAtLeast(user.getId(), projectId, ProjectRole.MEMBER)) {
            throw new ForbiddenException("missing project permission");
        }
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
        var job = Job.builder().project(project).spec(spec).build();
        job.persist();
        return new PreparedJob(JobView.of(job, List.of()), spec, resourceClass);
    }

    private User requireUser() {
        var user = userContext.get();
        if (user == null) {
            throw new UnauthorizedException();
        }
        return user;
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

    public List<JobLog> listLogs(UUID jobId, int page, int size) {
        require(jobId);
        return JobLog.listByJob(jobId, page, size);
    }

    public long countLogs(UUID jobId) {
        require(jobId);
        return JobLog.countByJob(jobId);
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
                .error(error)
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
