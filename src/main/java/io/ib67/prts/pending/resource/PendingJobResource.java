package io.ib67.prts.pending.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.CreateJobRequest;
import io.ib67.prts.dto.JobStatusView;
import io.ib67.prts.dto.PendingJobView;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobService;
import io.ib67.prts.project.JobCreateAccess;
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
    @Inject
    JobCreateAccess jobCreateAccess;

    @GET
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public List<JobStatusView> listPending(@ProjectId @PathParam("projectId") UUID projectId) {
        // One answer for the whole page: the permission is per project, not per entry.
        var mayCreate = jobCreateAccess.allowedIn(projectId);
        return pendingJobService.listByProject(projectId).stream()
                .map(it -> (JobStatusView) viewOf(it, mayCreate))
                .toList();
    }

    @GET
    @Path("/{pendingId}")
    @Transactional
    @RequirePermission(value = Perm.JOB_READ, defaultRole = ProjectRole.VIEWER)
    public JobStatusView getPending(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("pendingId") UUID pendingId) {
        return pendingJobService.findInProject(projectId, pendingId)
                .map(it -> viewOf(it, jobCreateAccess.allowedIn(projectId)))
                .orElseThrow(NotFoundException::new);
    }

    /** Only while it is still queued; once an attempt is in flight, the job it makes is the thing to cancel. */
    @POST
    @Path("/{pendingId}/cancel")
    @RequirePermission(value = Perm.JOB_CANCEL, defaultRole = ProjectRole.MEMBER)
    public JobStatusView cancelPending(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("pendingId") UUID pendingId) {
        var cancelled = pendingJobService.cancel(projectId, pendingId);
        return viewOf(cancelled, jobCreateAccess.allowedIn(projectId));
    }

    /**
     * The stored request is shown only to a caller who could post it — an entry is a create that has
     * not happened yet, so seeing one takes what making one takes, same as {@code JobView.createRequest}.
     */
    private PendingJobView viewOf(PendingJob pending, boolean mayCreate) {
        return PendingJobView.of(pending, mayCreate ? CreateJobRequest.of(pending.getRequest()) : null);
    }
}
