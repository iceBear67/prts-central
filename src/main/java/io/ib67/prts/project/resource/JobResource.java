package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.*;
import io.ib67.prts.pending.PendingJobService;
import io.ib67.prts.project.Artifact;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobLauncher;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.storage.StorageService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * Reading takes {@link ProjectRole#VIEWER}, acting on a job takes {@link ProjectRole#MEMBER}. The
 * path variable must stay named {@code projectId}: checks with no project argument read it off the
 * path.
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
    UserContext userContext;
    @Inject
    PermissionService permissionService;

    /** The project's own templates plus the global ones; another project's are not listed. */
    @GET
    @Path("/template")
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_READ, defaultRole = ProjectRole.VIEWER)
    public List<JobSpecTemplateView> listTemplates(@ProjectId @PathParam("projectId") UUID projectId) {
        return JobSpecTemplate.listVisibleFetched(projectId).stream()
                .map(JobSpecTemplateView::of)
                .toList();
    }

    @GET
    @Path("/template/{templateId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_READ, defaultRole = ProjectRole.VIEWER)
    public JobSpecTemplateView getTemplate(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("templateId") UUID templateId) {
        return JobSpecTemplate.findVisibleFetched(projectId, templateId)
                .map(JobSpecTemplateView::of)
                .orElseThrow(NotFoundException::new);
    }

    /**
     * Artifact names ride on {@code job:read}; the bytes need {@code job:artifact:read}. The stored
     * create request rides on {@code job:create}, so what a caller may do with the job decides how
     * much of it they get back, rather than a second endpoint.
     */
    @GET
    @Path("/{jobId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public JobView getJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        var job = jobService.findInProject(projectId, jobId).orElseThrow(NotFoundException::new);
        var request = job.toRequest();
        return JobView.of(
                job,
                Artifact.listByJob(jobId),
                //todo 这就是你说的按权限设置可见性？？？
                request != null && mayCreateJobs(projectId) ? CreateJobRequest.of(request) : null);
    }

    /**
     * The same test {@link #createJob} is gated by — a re-run is the client posting the request
     * back, so seeing one takes what using it takes.
     */
    private boolean mayCreateJobs(UUID projectId) {
        var user = userContext.get();
        return user != null
                && permissionService.allows(user.getId(), Perm.JOB_CREATE, projectId, ProjectRole.MEMBER);
    }

    /**
     * Queues the create rather than performing it, so the answer is the entry and not a job: the job
     * appears on it, as {@code jobId}, once a worker has taken the request. Authorizing here is what
     * makes that sound — the per-field override gates read the project off this request's path, and
     * the dispatcher that submits it later runs on a thread with no request at all.
     */
    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
    public Response createJob(
            @ProjectId @PathParam("projectId") UUID projectId, CreateJobRequest request) {
        if (request == null || request.templateId() == null) {
            throw new BadRequestException("templateId is required");
        }
        var authorized = jobLauncher.authorize(projectId, request.toRequest(), overridePermissions);
        var pending = pendingJobService.enqueue(projectId, authorized);
        return Response.status(Response.Status.CREATED).entity(PendingJobView.of(pending)).build();
    }

    /** Stops the job on its worker and marks it cancelled. */
    @POST
    @Path("/{jobId}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public JobView cancelJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        var job = jobService.cancel(projectId, jobId);
        return JobView.of(job, Artifact.listByJob(jobId));
    }

    /** Handing out the presigned URL is the download. */
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
        var window = clampLength(length);
        // Capped so the inclusive upper bound cannot overflow negative.
        var start = Math.clamp(offset, 0, Integer.MAX_VALUE - window);
        return JobLogPage.of(jobService.listLogs(projectId, jobId, start, window), start, window);
    }

    private int clampLength(Integer length) {
        var max = jobConfig.log().maxPageSize();
        if (length == null || length <= 0) {
            return max;
        }
        return Math.min(length, max);
    }
}
