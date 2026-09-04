package io.ib67.prts.project;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverrideAuthorizer;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.secret.SecretService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a {@link JobRequest} into a running {@link Job}: merges the template with the override, gates
 * what the spec may reach, persists the job and hands it to the scheduler. Reaching the project is the
 * caller's business, and so is the gate — the {@link JobSpecOverrideAuthorizer} is a parameter, because
 * a request may be {@link #authorize authorized} in the call that made it and {@link #launch launched}
 * later off a thread carrying neither the requester nor their request. Enforced here is only what the
 * spec itself may reach: the template's own project, the volume rule, and the resource class.
 *
 * <p>What an unplaceable job leaves behind is the caller's business too. {@link #launch} reports it and
 * changes nothing; undoing it is {@link JobService#discard}, with the job's other endings.
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
     * Runs every check {@link #launch} runs, persists nothing, and answers with the request as it is
     * to be replayed: cleared, and its resource class pinned to the one it resolved to — the same
     * pinning a re-run gets from {@link Job#toRequest()}.
     */
    public JobRequest authorize(UUID projectId, JobRequest request, JobSpecOverrideAuthorizer authorizer) {
        var resolved = QuarkusTransaction.requiringNew()
                .call(() -> resolve(projectId, request, authorizer));
        return request.withResourceClass(resolved.resourceClass().getName());
    }

    /**
     * Makes the job and hands it to the scheduler. Being unplaceable is reported, not thrown, and
     * leaves the job persisted and {@link JobState#PENDING} for the caller to
     * {@link JobService#discard discard}.
     */
    public CreatedJob launch(
            UUID projectId, UUID requestedBy, JobRequest request, JobSpecOverrideAuthorizer authorizer) {
        var prepared = QuarkusTransaction.requiringNew()
                .call(() -> prepare(projectId, requestedBy, request, authorizer));
        return dispatch(prepared);
    }

    /**
     * @param scheduled {@code false} when no worker could take the job. The row is untouched either
     *                  way; what to do about it is the caller's call.
     */
    public record CreatedJob(Job job, boolean scheduled) {
    }

    /**
     * Clears every override field without asking. Sound only for a request already cleared by
     * {@link #authorize}, and the caller vouches for that: nothing in the type can say so once the
     * request has been stored and read back.
     */
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
     * Hands a prepared job to the scheduler. A refusal is reported; only a throw is undone here, and
     * only because the caller could not: the id is not on the exception, and the offer may have
     * reached a worker that started a container, so a job that may have run has to remain visible.
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
        // Only the copy handed to the scheduler carries the secrets: the entity keeps the spec
        // without them, so neither the row nor a view read back off it can hold plaintext even if
        // the @JsonIgnore that already drops them were to go. Resolved per attempt, so a replayed
        // request picks up the project's secrets as they are now and never carries any itself.
        return new PreparedJob(
                job, resolved.spec().withSecret(secretService.resolve(projectId)), resolved.resourceClass());
    }

    /** Everything a create needs its caller cleared for, with nothing persisted yet. */
    private ResolvedCreate resolve(UUID projectId, JobRequest request, JobSpecOverrideAuthorizer authorizer) {
        var project = projectService.findById(projectId).orElseThrow(NotFoundException::new);
        // Scoped, not merely fetched: a template of another project must not be reachable from here.
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
}
