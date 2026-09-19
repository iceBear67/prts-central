package io.ib67.prts.agent.worker.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.WorkerEntity;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.WorkerRemovalView;
import io.ib67.prts.dto.WorkerView;
import io.ib67.prts.dto.WorkerVolumeView;
import io.ib67.prts.dto.job.JobView;
import io.ib67.prts.dto.request.RenameWorkerRequest;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobState;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.UUID;

/**
 * Administrative endpoints for managing registered workers.
 */
@Path("/worker")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class WorkerResource {
    @Inject
    WorkerService workerService;
    @Inject
    AdminConfig adminConfig;
    @Inject
    JobService jobService;

    @GET
    @Transactional
    public Page<WorkerView> listWorkers(
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                WorkerEntity.listPage(start, window).stream().map(this::view).toList(),
                start,
                window,
                WorkerEntity.count());
    }

    @GET
    @Path("/{id}")
    @Transactional
    public WorkerView getWorker(@PathParam("id") UUID id) {
        return view(WorkerEntity.<WorkerEntity>findByIdOptional(id).orElseThrow(NotFoundException::new));
    }

    @POST
    @Path("/{id}/disable")
    public WorkerView disableWorker(@PathParam("id") UUID id) {
        return view(workerService.setDisabled(id, true));
    }

    @POST
    @Path("/{id}/enable")
    public WorkerView enableWorker(@PathParam("id") UUID id) {
        return view(workerService.setDisabled(id, false));
    }

    /**
     * Renames a worker.
     */
    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public WorkerView renameWorker(
            @PathParam("id") UUID id,
            @NotNull(message = "a request body is required") @Valid RenameWorkerRequest request) {
        return view(workerService.rename(id, request.name()));
    }

    /**
     * Disconnects a worker's active session. Any running jobs on the worker will fail.
     */
    @POST
    @Path("/{id}/disconnect")
    public void disconnectWorker(@PathParam("id") UUID id) {
        WorkerEntity.<WorkerEntity>findByIdOptional(id).orElseThrow(NotFoundException::new);
        workerService.disconnect(id);
    }

    /**
     * Deletes a worker registration. The worker must be disconnected either way, and without
     * {@code force} must also hold no unfinished job and host no volume.
     *
     * <p>{@code force} is for a host that is never coming back: it drops the volume rows without the
     * acknowledgment a release normally needs and fails the jobs the worker was holding. The response
     * says how much was abandoned rather than reclaimed.
     */
    @DELETE
    @Path("/{id}")
    public WorkerRemovalView deleteWorker(
            @PathParam("id") UUID id, @QueryParam("force") @DefaultValue("false") boolean force) {
        return workerService.delete(id, force);
    }

    /**
     * Lists the jobs this worker has run, newest first — a timeline, not only what it holds now.
     *
     * @param state narrows to one state; omitted covers terminal states too
     * @param since lower bound on the job's creation time
     */
    @GET
    @Path("/{id}/job")
    @Transactional
    public Page<JobView> listWorkerJobs(
            @PathParam("id") UUID id,
            @QueryParam("state") @Nullable JobState state,
            @QueryParam("since") @Nullable Instant since,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        WorkerEntity.<WorkerEntity>findByIdOptional(id).orElseThrow(NotFoundException::new);
        var filter = Job.Filter.builder().worker(id).state(state).since(since).build();
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                jobService.viewOf(Job.listVisible(filter, start, window)),
                start,
                window,
                Job.countVisible(filter));
    }

    /** Lists the volumes hosted on this worker across all projects. */
    @GET
    @Path("/{id}/volume")
    @Transactional
    public Page<WorkerVolumeView> listWorkerVolumes(
            @PathParam("id") UUID id,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        WorkerEntity.<WorkerEntity>findByIdOptional(id).orElseThrow(NotFoundException::new);
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                WorkerVolume.search(id, null, null, null, start, window).stream()
                        .map(WorkerVolumeView::of)
                        .toList(),
                start,
                window,
                WorkerVolume.countSearch(id, null, null, null));
    }

    private WorkerView view(WorkerEntity row) {
        return WorkerView.of(row, workerService.getWorker(row.getId()).orElse(null));
    }
}
