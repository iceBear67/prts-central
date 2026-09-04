package io.ib67.prts.pending.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.PendingJobView;
import io.ib67.prts.pending.PendingJobService;
import io.ib67.prts.project.ProjectRole;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * The queue as seen from outside: what {@code POST /job/create} left behind, and the one way to call
 * it back. Entries are made there, not here — reading one costs what reading a job costs and
 * cancelling one what cancelling a job costs, since an entry is a create that has not happened yet.
 * The path variable must stay named {@code projectId} — checks with no project argument read it off
 * the path.
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

    /** Only while it is still queued; once an attempt is in flight, the job it makes is the thing to cancel. */
    @POST
    @Path("/{pendingId}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public PendingJobView cancelPending(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("pendingId") UUID pendingId) {
        return PendingJobView.of(pendingJobService.cancel(projectId, pendingId));
    }
}
