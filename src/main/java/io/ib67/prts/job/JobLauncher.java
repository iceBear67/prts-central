package io.ib67.prts.job;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverrideAuthorizer;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobRequest;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.Project;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.secret.SecretService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves, persists, and launches jobs from {@link JobRequest} payloads.
 *
 * <p>Merges template specifications with overrides, validates permissions,
 * attaches project secrets at runtime, and delegates execution to the scheduler.
 */
@ApplicationScoped
public class JobLauncher {

    @Inject
    ProjectService projectService;
    @Inject
    JobService jobService;
    @Inject
    WorkerService workerService;
    @Inject
    SecretService secretService;

    /**
     * Validates and authorizes a job request without persisting or launching it.
     *
     * @return The validated request with its resolved resource class pinned.
     */
    public JobRequest authorize(UUID projectId, JobRequest request, JobSpecOverrideAuthorizer authorizer) {
        var resolved = QuarkusTransaction.requiringNew()
                .call(() -> resolve(projectId, request, authorizer));
        return request.withResourceClass(resolved.resourceClass().getName());
    }

    /**
     * Persists the job and attempts to schedule it on an available worker.
     */
    public CreatedJob launch(
            UUID projectId, UUID requestedBy, JobRequest request, JobSpecOverrideAuthorizer authorizer) {
        var prepared = QuarkusTransaction.requiringNew()
                .call(() -> prepare(projectId, requestedBy, request, authorizer));
        return dispatch(prepared);
    }

    /**
     * @param scheduled Whether the job was successfully placed on a worker.
     */
    public record CreatedJob(Job job, boolean scheduled) {
        public CreatedJob {
            Objects.requireNonNull(job, "job");
        }
    }

    /** Authorizer implementation that accepts all override values without checking permissions. */
    public static final JobSpecOverrideAuthorizer PRE_AUTHORIZED = new JobSpecOverrideAuthorizer() {
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
     * Dispatches a prepared job to the worker service.
     */
    private CreatedJob dispatch(PreparedJob prepared) {
        var jobId = prepared.job().getId();
        boolean scheduled;
        try {
            scheduled = workerService.schedule(jobId, prepared.resourceClass(), prepared.spec());
        } catch (RuntimeException e) {
            jobService.applyState(jobId, JobState.FAILED);
            throw e;
        }
        return new CreatedJob(prepared.job(), scheduled);
    }

    private PreparedJob prepare(
            UUID projectId, UUID requestedBy, JobRequest request, JobSpecOverrideAuthorizer authorizer) {
        var resolved = resolve(projectId, request, authorizer);
        var job = Job.builder()
                .project(resolved.project())
                .spec(resolved.spec())
                .resourceClass(resolved.resourceClass())
                .templateId(request.templateId())
                .createOverride(request.override())
                .requestedBy(requestedBy)
                .build();
        job.persist();
        // Secrets are attached only to the in-memory spec sent to the scheduler, never persisted.
        return new PreparedJob(
                job, resolved.spec().withSecret(secretService.resolve(projectId)), resolved.resourceClass());
    }

    private ResolvedCreate resolve(UUID projectId, JobRequest request, JobSpecOverrideAuthorizer authorizer) {
        var project = projectService.findById(projectId).orElseThrow(NotFoundException::new);
        var template = JobSpecTemplate.findVisibleFetched(projectId, request.templateId())
                .orElseThrow(() -> new NotFoundException("no such template: " + request.templateId()));
        if (template.getSpec() == null) {
            throw new BadRequestException("template has no job spec");
        }
        var override = request.override();
        var spec = override == null
                ? template.getSpec()
                : override.applyTo(template.getSpec(), authorizer);
        spec.requireVolumesIn(projectId);
        return new ResolvedCreate(
                project,
                spec,
                resolveResourceClass(projectId, request.resourceClass(), template.getResourceClass(), authorizer));
    }

    /**
     * Resolves the resource class, validating permissions if overriding the template's class.
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
     * Ensures the resource class is either global or belongs to the specified project.
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
        public PreparedJob {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(resourceClass, "resourceClass");
        }
    }

    /** @param spec the merged spec <em>without</em> secrets: nothing is running yet. */
    private record ResolvedCreate(Project project, JobSpec spec, ResourceClass resourceClass) {
        public ResolvedCreate {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(resourceClass, "resourceClass");
        }
    }
}
