package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.*;
import io.ib67.prts.dto.job.*;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobService;
import io.ib67.prts.project.JobAccess;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobLauncher;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.project.entity.Artifact;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobRequest;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.storage.StorageService;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
    JobConfig jobConfig;
    @Inject
    JobAccess jobAccess;
    @Inject
    ProjectService projectService;

    /** Lists visible templates (project-specific and global) for a project. */
    @GET
    @Path("/template")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public List<JobSpecTemplateView> listTemplates(@ProjectId @PathParam("projectId") UUID projectId) {
        // The listing below queries no project row of its own — global templates match any id — so
        // without this a caller who passes the permission check on a nonexistent project gets 200.
        projectService.require(projectId);
        var withSpec = jobAccess.mayReadTemplate(projectId);
        return JobSpecTemplate.listVisibleFetched(projectId).stream()
                .map(template -> JobSpecTemplateView.of(template, withSpec))
                .toList();
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

    /** Lists both running/completed jobs and queued pending jobs in reverse chronological order. */
    @GET
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public List<JobStatusView> listJobs(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = clampLength(length, jobConfig.list().maxPageSize());
        var start = Math.clamp(offset, 0, Integer.MAX_VALUE - window);
        var depth = start + window;
        var mayCreate = jobAccess.mayCreate(projectId);
        var jobs = jobService.listVisible(projectId, depth);
        var artifacts = Artifact.listByJobs(jobs.stream().map(Job::getId).toList()).stream()
                .collect(Collectors.groupingBy(artifact -> artifact.getJob().getId()));
        return Stream.<JobStatusView>concat(
                        jobs.stream().map(job -> JobView.of(job,
                                artifacts.getOrDefault(job.getId(), List.of()), requestFor(mayCreate, job.toRequest()))),
                        PendingJob.listUnplacedByProject(projectId, depth).stream()
                                .map(pending -> PendingJobView.of(pending, requestFor(mayCreate, pending.getRequest()))))
                .sorted(Comparator.comparing(JobStatusView::createdAt).reversed())
                .skip(start)
                .limit(window)
                .toList();
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
            return viewOf(projectId, job.get());
        }
        var pending = pendingJobService.findInProject(projectId, jobId)
                .orElseThrow(NotFoundException::new);
        // Only ever set once an attempt was taken, so an entry still waiting reads as itself.
        var dispatched = pending.getJobId() == null
                ? Optional.<Job>empty()
                : jobService.findInProject(projectId, pending.getJobId());
        return dispatched
                .map(it -> (JobStatusView) viewOf(projectId, it))
                .orElseGet(() -> PendingJobView.of(pending,
                        requestFor(jobAccess.mayCreate(projectId), pending.getRequest())));
    }

    private JobView viewOf(UUID projectId, Job job) {
        return JobView.of(job, Artifact.listByJob(job.getId()),
                requestFor(jobAccess.mayCreate(projectId), job.toRequest()));
    }

    /** Returns the creation request details if the caller has permission to create jobs. */
    @Nullable
    private static CreateJobRequest requestFor(boolean mayCreate, @Nullable JobRequest request) {
        return request != null && mayCreate ? CreateJobRequest.of(request) : null;
    }

    /** Enqueues a new job creation request after authorizing requested overrides. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @APIResponse(
            responseCode = "201",
            description = "The queue entry the create became.",
            content = @Content(schema = @Schema(implementation = JobStatusView.class)))
    @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
    public JobStatusView createJob(
            @ProjectId @PathParam("projectId") UUID projectId, CreateJobRequest request) {
        if (request == null || request.templateId() == null) {
            throw new BadRequestException("templateId is required");
        }
        var authorized = jobLauncher.authorize(projectId, request.toRequest(), overridePermissions);
        var pending = pendingJobService.enqueue(projectId, authorized);
        return PendingJobView.of(pending, CreateJobRequest.of(pending.getRequest()));
    }

    /** Cancels an active job or queued pending job. */
    @POST
    @Path("/{jobId}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public JobStatusView cancelJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        if (jobService.findInProject(projectId, jobId).isEmpty()) {
            var pending = pendingJobService.findInProject(projectId, jobId)
                    .orElseThrow(NotFoundException::new);
            if (pending.getJobId() == null) {
                var entry = pendingJobService.cancel(projectId, jobId);
                return PendingJobView.of(entry, requestFor(jobAccess.mayCreate(projectId), entry.getRequest()));
            }
            jobId = pending.getJobId();
        }
        return JobView.of(jobService.cancel(projectId, jobId), Artifact.listByJob(jobId));
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

    @GET
    @Path("/{jobId}/log")
    @Transactional
    @RequirePermission(value = Perm.JOB_LOG_READ, defaultRole = ProjectRole.VIEWER)
    public JobLogPage getJobLogs(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("jobId") UUID jobId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = clampLength(length, jobConfig.log().maxPageSize());
        var start = Math.clamp(offset, 0, Integer.MAX_VALUE - window);
        return JobLogPage.of(jobService.listLogs(projectId, jobId, start, window), start, window);
    }

    private static int clampLength(@Nullable Integer length, int max) {
        if (length == null || length <= 0) {
            return max;
        }
        return Math.min(length, max);
    }
}
