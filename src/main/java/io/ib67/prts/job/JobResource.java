package io.ib67.prts.job;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.*;
import io.ib67.prts.dto.job.*;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.dto.request.CreateTemplateRequest;
import io.ib67.prts.dto.request.UpdateTemplateRequest;
import io.ib67.prts.pending.PendingJobService;
import io.ib67.prts.project.ProjectConfig;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.storage.ArtifactService;
import io.ib67.prts.storage.StorageService;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * REST endpoint managing jobs, queued tasks, templates, logs, and artifacts within a project.
 */
@Path("/project/{projectId}/job")
@Produces(MediaType.APPLICATION_JSON)
public class JobResource {
    @Inject
    JobService jobService;
    @Inject
    JobLauncher jobLauncher;
    @Inject
    JobSpecOverridePermissions overridePermissions;
    @Inject
    PendingJobService pendingJobService;
    @Inject
    StorageService storageService;
    @Inject
    ArtifactService artifactService;
    @Inject
    JobConfig jobConfig;
    @Inject
    JobAccess jobAccess;
    @Inject
    ProjectService projectService;
    @Inject
    ProjectConfig projectConfig;

    /** Lists visible templates (project-specific and global) for a project. */
    @GET
    @Path("/template")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public Page<JobSpecTemplateView> listTemplates(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        // Ensure the project exists before listing templates.
        projectService.require(projectId);
        var withSpec = jobAccess.mayReadTemplate(projectId);
        var window = Pages.clampLength(length, projectConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                JobSpecTemplate.listVisibleFetched(projectId, start, window).stream()
                        .map(template -> JobSpecTemplateView.of(template, withSpec))
                        .toList(),
                start,
                window,
                JobSpecTemplate.countVisible(projectId));
    }

    @GET
    @Path("/template/{templateId}")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public JobSpecTemplateView getTemplate(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("templateId") UUID templateId) {
        return JobSpecTemplate.findVisibleFetched(projectId, templateId)
                .map(template -> JobSpecTemplateView.of(template, jobAccess.mayReadTemplate(projectId)))
                .orElseThrow(NotFoundException::new);
    }

    /** Defines a template scoped to this project. Global templates are the admin API's to define. */
    @POST
    @Path("/template")
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_MANAGE, defaultRole = ProjectRole.OWNER)
    public JobSpecTemplateView createTemplate(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid CreateTemplateRequest request) {
        var project = projectService.requireWritable(projectId);
        var spec = request.spec().toSpec();
        spec.requireVolumesIn(projectId);
        var template = JobSpecTemplate.builder()
                .name(request.name())
                .spec(spec)
                .resourceClass(requireClass(projectId, request.resourceClass()))
                .project(project)
                .build();
        template.persist();
        // Return the full spec to the creator without requiring job:template:read permission.
        return JobSpecTemplateView.of(template, true);
    }

    /**
     * Applies the fields the caller supplied to one of this project's own templates.
     *
     * <p>Updating rather than replacing matters because the ID is what a task, a queued job and a
     * re-run payload hold: delete-and-recreate would leave all three pointing at a template that no
     * longer exists. A supplied {@code spec} replaces the stored one whole, as it does on the admin
     * half — merging cannot remove an environment entry.
     */
    @PATCH
    @Path("/template/{templateId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_MANAGE, defaultRole = ProjectRole.OWNER)
    public JobSpecTemplateView updateTemplate(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("templateId") UUID templateId,
            @NotNull(message = "a request body is required") @Valid UpdateTemplateRequest request) {
        projectService.requireWritable(projectId);
        var template = JobSpecTemplate.findVisibleFetched(projectId, templateId)
                .orElseThrow(NotFoundException::new);
        if (template.getProject() == null) {
            throw new ClientErrorException(
                    "a global template is not this project's to edit: " + templateId,
                    Response.Status.CONFLICT);
        }
        if (request.name() != null) {
            template.setName(request.name());
        }
        if (request.resourceClass() != null) {
            template.setResourceClass(requireClass(projectId, request.resourceClass()));
        }
        if (request.spec() != null) {
            var spec = request.spec().toSpec();
            spec.requireVolumesIn(projectId);
            template.setSpec(spec);
        }
        // The editor just wrote it, so the answer carries it whatever job:template:read says.
        return JobSpecTemplateView.of(template, true);
    }

    /** The class named on a template, refused when it is not this project's to run in. */
    private static ResourceClass requireClass(UUID projectId, String name) {
        return ResourceClass.findByName(name)
                .orElseThrow(() -> new NotFoundException("no such resource class: " + name))
                .requireAvailableTo(projectId);
    }

    /**
     * Deletes a project-scoped template.
     */
    @DELETE
    @Path("/template/{templateId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_MANAGE, defaultRole = ProjectRole.OWNER)
    public void deleteTemplate(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("templateId") UUID templateId) {
        projectService.requireWritable(projectId);
        var template = JobSpecTemplate.findVisibleFetched(projectId, templateId)
                .orElseThrow(NotFoundException::new);
        if (template.getProject() == null) {
            throw new ClientErrorException(
                    "a global template is not this project's to delete: " + templateId,
                    Response.Status.CONFLICT);
        }
        template.delete();
    }

    /**
     * Lists both running/completed jobs and queued pending jobs in reverse chronological order.
     *
     * @param taskId narrows the listing to one task's jobs; omitted lists the whole project's
     * @param state  narrows it to one state of either table — see {@link JobStatus}
     */
    @GET
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public Page<JobStatusView> listJobs(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("task") @Nullable UUID taskId,
            @QueryParam("state") @Nullable JobStatus state,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, jobConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        var depth = start + window;
        var jobs = jobService.listVisible(projectId, taskId, state, depth);
        var queued = pendingJobService.listUnplaced(projectId, taskId, state, depth);
        return new Page<>(
                Stream.<JobStatusView>concat(
                                jobService.viewOf(projectId, jobs).stream(),
                                pendingJobService.viewOf(projectId, queued).stream())
                        .sorted(Comparator.comparing(JobStatusView::createdAt).reversed())
                        .skip(start)
                        .limit(window)
                        .toList(),
                start,
                window,
                // The listing merges two tables, so its total is the sum of what each contributes.
                jobService.countVisible(projectId, taskId, state)
                        + pendingJobService.countUnplaced(projectId, taskId, state));
    }

    /**
     * Retrieves status for a job or a queued pending job by its ID.
     */
    @GET
    @Path("/{jobId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public JobStatusView getJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        var job = jobService.findInProject(projectId, jobId);
        if (job.isPresent()) {
            return jobService.viewOf(projectId, job.get());
        }
        var pending = pendingJobService.findInProject(projectId, jobId)
                .orElseThrow(NotFoundException::new);
        // If a worker has been assigned, return the created Job; otherwise return the pending job entry.
        var dispatched = pending.getJobId() == null
                ? Optional.<Job>empty()
                : jobService.findInProject(projectId, pending.getJobId());
        return dispatched
                .map(it -> (JobStatusView) jobService.viewOf(projectId, it))
                .orElseGet(() -> pendingJobService.viewOf(projectId, pending));
    }

    /**
     * Enqueues a new job creation request after authorizing requested overrides.
     *
     * <p>A request naming a task is validated by {@code JobLauncher.resolve}, which needs the task
     * anyway to merge its scope in — a closing or closed task conflicts there rather than here.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @APIResponse(
            responseCode = "201",
            description = "The created or queued job.",
            content = @Content(schema = @Schema(implementation = JobStatusView.class)))
    @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
    public JobStatusView createJob(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid CreateJobRequest request) {
        projectService.requireWritable(projectId);
        var authorized = jobLauncher.authorize(projectId, request.toRequest(), overridePermissions);
        // The caller holds job:create by definition here, so the request always comes back.
        return pendingJobService.viewOf(projectId, pendingJobService.enqueue(projectId, authorized));
    }

    /** Cancels an active job or queued pending job. */
    @POST
    @Path("/{jobId}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public JobStatusView cancelJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        projectService.requireWritable(projectId);
        if (jobService.findInProject(projectId, jobId).isEmpty()) {
            var pending = pendingJobService.findInProject(projectId, jobId)
                    .orElseThrow(NotFoundException::new);
            if (pending.getJobId() == null) {
                return pendingJobService.viewOf(projectId, pendingJobService.cancel(projectId, jobId));
            }
            jobId = pending.getJobId();
        }
        return jobService.viewOf(projectId, jobService.cancel(projectId, jobId));
    }

    @GET
    @Path("/artifact/{artifactId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_ARTIFACT_READ, defaultRole = ProjectRole.VIEWER)
    public PresignedUrlView getArtifactUrl(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("artifactId") UUID artifactId) {
        var artifact = Artifact.findInProject(projectId, artifactId).orElseThrow(NotFoundException::new);
        return PresignedUrlView.of(storageService.presignGet(artifact.getObjectKey()));
    }

    /** Deletes an artifact and its underlying storage object. */
    @DELETE
    @Path("/artifact/{artifactId}")
    @RequirePermission(value = Perm.JOB_ARTIFACT_DELETE, defaultRole = ProjectRole.OWNER)
    public void deleteArtifact(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("artifactId") UUID artifactId) {
        projectService.requireWritable(projectId);
        artifactService.delete(projectId, artifactId);
    }

    @GET
    @Path("/{jobId}/log")
    @Transactional
    @RequirePermission(value = Perm.JOB_LOG_READ, defaultRole = ProjectRole.VIEWER)
    public Page<JobLogView> getJobLogs(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("jobId") UUID jobId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, jobConfig.log().maxPageSize());
        return jobService.logsOf(projectId, jobId, Pages.clampOffset(offset, window), window);
    }
}
