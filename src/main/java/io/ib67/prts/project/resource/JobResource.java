package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.*;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.storage.StorageService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

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
    StorageService storageService;
    @Inject
    JobConfig jobConfig;

    /** Templates are global; the project in the path only gates who may browse them. */
    @GET
    @Path("/template")
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_READ, defaultRole = ProjectRole.VIEWER)
    public List<JobSpecTemplateView> listTemplates() {
        return JobSpecTemplate.listAllFetched().stream()
                .map(JobSpecTemplateView::of)
                .toList();
    }

    @GET
    @Path("/template/{templateId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_READ, defaultRole = ProjectRole.VIEWER)
    public JobSpecTemplateView getTemplate(@PathParam("templateId") UUID templateId) {
        return JobSpecTemplate.findByIdFetched(templateId)
                .map(JobSpecTemplateView::of)
                .orElseThrow(NotFoundException::new);
    }

    /** Artifact names ride on {@code job:read}; the bytes need {@code job:artifact:read}. */
    @GET
    @Path("/{jobId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public JobView getJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        var job = jobService.findInProject(projectId, jobId).orElseThrow(NotFoundException::new);
        return JobView.of(job, jobService.listArtifacts(projectId, jobId));
    }

    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
    public JobView createJob(
            @ProjectId @PathParam("projectId") UUID projectId, CreateJobRequest request) {
        if (request == null || request.templateId() == null) {
            throw new BadRequestException("templateId is required");
        }
        return JobView.of(jobService.createFromTemplate(
                projectId,
                request.templateId(),
                request.override(),
                request.resourceClass(),
                request.prompt()));
    }

    /**
     * The stored create request, shaped to be posted back to {@code /create}: re-running is the
     * client replaying it, so fetching one takes the same permission as using it.
     */
    @GET
    @Path("/{jobId}/request")
    @Transactional
    @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
    public CreateJobRequest getCreateRequest(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        var job = jobService.requireInProject(projectId, jobId);
        if (job.getTemplateId() == null) {
            throw new NotFoundException("job records no create request: " + jobId);
        }
        return new CreateJobRequest(
                job.getTemplateId(), job.getCreateOverride(), job.getCreateResourceClass(), job.getCreatePrompt());
    }

    /** Stops the job on its worker and marks it cancelled. */
    @POST
    @Path("/{jobId}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public JobView cancelJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("jobId") UUID jobId) {
        var job = jobService.cancel(projectId, jobId);
        return JobView.of(job, jobService.listArtifacts(projectId, jobId));
    }

    /** Handing out the presigned URL is the download. */
    @GET
    @Path("/artifact/{artifactId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_ARTIFACT_READ, defaultRole = ProjectRole.VIEWER)
    public PresignedUrlView getArtifactUrl(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("artifactId") UUID artifactId) {
        var artifact = jobService.findArtifact(projectId, artifactId).orElseThrow(NotFoundException::new);
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
        var start = Math.min(Math.max(offset, 0), Integer.MAX_VALUE - window);
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
