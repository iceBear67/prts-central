package io.ib67.prts.agent.worker.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.WorkerView;
import io.ib67.prts.dto.WorkerVolumeView;
import io.ib67.prts.dto.job.JobView;
import io.ib67.prts.dto.request.RenameWorkerRequest;
import io.ib67.prts.project.entity.Artifact;
import io.ib67.prts.project.entity.Job;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
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

    @GET
    @Transactional
    public List<WorkerView> listWorkers() {
        return Worker.<Worker>listAll().stream().map(this::view).toList();
    }

    @GET
    @Path("/{id}")
    @Transactional
    public WorkerView getWorker(@PathParam("id") UUID id) {
        return view(Worker.<Worker>findByIdOptional(id).orElseThrow(NotFoundException::new));
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
        Worker.<Worker>findByIdOptional(id).orElseThrow(NotFoundException::new);
        workerService.disconnect(id);
    }

    /** Deletes a worker registration. The worker must be disconnected with no active jobs or volumes. */
    @DELETE
    @Path("/{id}")
    public void deleteWorker(@PathParam("id") UUID id) {
        workerService.delete(id);
    }

    /** Lists all active jobs currently assigned to the worker. */
    @GET
    @Path("/{id}/job")
    @Transactional
    public List<JobView> listWorkerJobs(@PathParam("id") UUID id) {
        Worker.<Worker>findByIdOptional(id).orElseThrow(NotFoundException::new);
        return Job.listOpenByWorker(id).stream()
                .map(job -> JobView.of(job, Artifact.listByJob(job.getId())))
                .toList();
    }

    /** Lists all volumes hosted on this worker across all projects. */
    @GET
    @Path("/{id}/volume")
    @Transactional
    public List<WorkerVolumeView> listWorkerVolumes(@PathParam("id") UUID id) {
        Worker.<Worker>findByIdOptional(id).orElseThrow(NotFoundException::new);
        return WorkerVolume.listByWorker(id).stream().map(WorkerVolumeView::of).toList();
    }

    private WorkerView view(Worker row) {
        return WorkerView.of(row, workerService.getWorker(row.getId()).orElse(null));
    }
}
