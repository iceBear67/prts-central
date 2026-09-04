package io.ib67.prts.pending.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.CreateJobRequest;
import io.ib67.prts.dto.PendingJobView;
import io.ib67.prts.pending.PendingJobService;
import io.ib67.prts.project.ProjectRole;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * Queueing a job costs what creating one costs, and reading the queue costs what reading a job costs:
 * an entry is a create that has not happened yet, so no permission of its own is warranted. The path
 * variable must stay named {@code projectId} — checks with no project argument read it off the path.
 */
@Path("/project/{projectId}/job/pending")
@Produces(MediaType.APPLICATION_JSON)
public class PendingJobResource {
    @Inject
    PendingJobService pendingJobService;

    @GET
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public List<PendingJobView> listPending(@ProjectId @PathParam("projectId") UUID projectId) {
        return pendingJobService.listByProject(projectId).stream()
                .map(PendingJobView::of)
                .toList();
    }

    @GET
    @Path("/{pendingId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public PendingJobView getPending(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("pendingId") UUID pendingId) {
        return pendingJobService.findInProject(projectId, pendingId)
                .map(PendingJobView::of)
                .orElseThrow(NotFoundException::new);
    }

    /**
     * Accepts the same request {@code /job/create} does and answers 201 with the entry rather than a
     * job: the create is authorized here and made later. A caller who wants it now or not at all
     * still has {@code /job/create} and its 409.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(value = Perm.JOB_CREATE, defaultRole = ProjectRole.MEMBER)
    public Response enqueue(
            @ProjectId @PathParam("projectId") UUID projectId, CreateJobRequest request) {
        if (request == null || request.templateId() == null) {
            throw new BadRequestException("templateId is required");
        }
        var pending = pendingJobService.enqueue(
                projectId, request.templateId(), request.override(), request.resourceClass());
        return Response.status(Response.Status.CREATED).entity(PendingJobView.of(pending)).build();
    }

    /** Only while it is still queued; once an attempt is in flight, the job it makes is the thing to cancel. */
    @POST
    @Path("/{pendingId}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public PendingJobView cancelPending(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("pendingId") UUID pendingId) {
        return PendingJobView.of(pendingJobService.cancel(projectId, pendingId));
    }
}
