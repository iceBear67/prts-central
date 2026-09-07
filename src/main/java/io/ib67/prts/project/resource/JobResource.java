package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.*;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobService;
import io.ib67.prts.project.JobAccess;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobLauncher;
import io.ib67.prts.project.JobService;
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
 * Reading takes {@link ProjectRole#VIEWER}, acting on a job takes {@link ProjectRole#MEMBER}. The
 * path variable must stay named {@code projectId}: checks with no project argument read it off the
 * path.
 *
 * <p>A queue entry is addressed exactly like the job it will become: one list, one read, one cancel,
 * each answering a {@link JobStatusView} whose {@code type} says which of the two it found.
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

    /**
     * The project's own templates plus the global ones; another project's are not listed. Every
     * reader gets id and name, which is all a create needs; the content takes {@code job:template:read}.
     */
    @GET
    @Path("/template")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public List<JobSpecTemplateView> listTemplates(@ProjectId @PathParam("projectId") UUID projectId) {
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

    /**
     * Jobs and the queue entries that are not jobs yet, newest first. Two tables, so each is read
     * {@code offset + length} deep and the page cut out of the merge — fine for paging, not for
     * reaching the bottom of a long history.
     */
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
     * Takes either id the client may be holding: a job's, or that of the queue entry it came from,
     * which is followed to the job once one exists. So the id handed out at create time keeps working
     * for the life of the run, and {@code type} on the answer says which of the two it found.
     *
     * <p>Artifact names ride on {@code job:read}; the bytes need {@code job:artifact:read}. The stored
     * create request rides on {@code job:create}, so what a caller may do with the job decides how
     * much of it they get back, rather than a second endpoint.
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

    /**
     * The stored request, but only for a caller who could post it back — a re-run is the client doing
     * exactly that, so seeing one takes what using it takes.
     */
    @Nullable
    private static CreateJobRequest requestFor(boolean mayCreate, @Nullable JobRequest request) {
        return request != null && mayCreate ? CreateJobRequest.of(request) : null;
    }

    /**
     * Queues the create rather than performing it, so the answer is the entry and not a job: the job
     * appears on it, as {@code jobId}, once a worker has taken the request. Authorizing here is what
     * makes that sound — the per-field override gates read the project off this request's path, and
     * the dispatcher that submits it later runs on a thread with no request at all.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    // 201 by annotation rather than by returning a Response, so the declared type stays JobStatusView
    // — that is what puts the discriminator on the wire and a schema in the document.
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
        // The creator holds job:create by definition — this endpoint is gated on it.
        return PendingJobView.of(pending, CreateJobRequest.of(pending.getRequest()));
    }

    /**
     * Either id again, like {@link #getJob}: a job is stopped on its worker and marked cancelled, an
     * entry still waiting is taken off the queue, and an entry whose job exists is cancelled as that
     * job. The two refuse on different conditions — a terminal job, an entry whose attempt is in flight
     * — and {@code type} on the answer says which rule applied.
     */
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
        var window = clampLength(length, jobConfig.log().maxPageSize());
        // Capped so the inclusive upper bound cannot overflow negative.
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
