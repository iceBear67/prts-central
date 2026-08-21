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
 * Jobs are addressed through their project, so every permission check here has a project to be
 * scoped to: reading takes {@link ProjectRole#VIEWER}, acting on a job takes
 * {@link ProjectRole#MEMBER}. Job specs are looked up by their own id and matched against that
 * project.
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

    /**
     * Templates are not owned by a project; the path prefix says where they are about to be used,
     * and browsing them is gated by that project — which the check reads off the path itself.
     */
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
    @Path("/template/{id}")
    @Transactional
    @RequirePermission(value = Perm.JOB_TEMPLATE_READ, defaultRole = ProjectRole.VIEWER)
    public JobSpecTemplateView getTemplate(@PathParam("id") UUID id) {
        return JobSpecTemplate.findByIdFetched(id)
                .map(JobSpecTemplateView::of)
                .orElseThrow(NotFoundException::new);
    }

    /**
     * The job row and the metadata of what it produced. Reading the log lines and downloading an
     * artifact are separate permissions; the names and sizes listed here are not.
     */
    @GET
    @Path("/{id}")
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public JobView getJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("id") UUID id) {
        var job = jobService.findInProject(projectId, id).orElseThrow(NotFoundException::new);
        return JobView.of(job, jobService.listArtifacts(projectId, id));
    }

    /**
     * The path variable is named {@code projectId} on purpose: the spec-override permissions checked
     * while this runs take no project argument and read it off the path.
     */
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

    /** Runs the spec this job was created with again, as a new job. */
    @POST
    @Path("/{id}/rerun")
    @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
    public JobView rerunJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("id") UUID id) {
        return JobView.of(jobService.rerun(projectId, id));
    }

    /** Stops the job on its worker and marks it cancelled. */
    @POST
    @Path("/{id}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public JobView cancelJob(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("id") UUID id) {
        var job = jobService.cancel(projectId, id);
        return JobView.of(job, jobService.listArtifacts(projectId, id));
    }

    /** The presigned URL is the artifact, so handing it out is the artifact read itself. */
    @GET
    @Path("/artifact/{id}")
    @Transactional
    @RequirePermission(value = Perm.JOB_ARTIFACT_READ, defaultRole = ProjectRole.VIEWER)
    public PresignedUrlView getArtifactUrl(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("id") UUID id) {
        var artifact = jobService.findArtifact(projectId, id).orElseThrow(NotFoundException::new);
        return PresignedUrlView.of(storageService.presignGet(artifact.getObjectKey()));
    }

    @GET
    @Path("/{id}/log")
    @Transactional
    @RequirePermission(value = Perm.JOB_LOG_READ, defaultRole = ProjectRole.VIEWER)
    public JobLogPage getJobLogs(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("id") UUID id,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var start = Math.max(offset, 0);
        var window = clampLength(length);
        return JobLogPage.of(jobService.listLogs(projectId, id, start, window), start, window);
    }

    private int clampLength(Integer length) {
        var max = jobConfig.log().maxPageSize();
        if (length == null || length <= 0) {
            return max;
        }
        return Math.min(length, max);
    }
}
