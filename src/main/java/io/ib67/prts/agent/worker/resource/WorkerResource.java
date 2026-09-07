package io.ib67.prts.agent.worker.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.WorkerView;
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
 * The worker roster, for administrators: every worker that ever registered, connected or not.
 * Workers belong to no project, so there is no role to stand in for the permission.
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

    /** Stops offering it jobs; what it is running finishes as usual. */
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

    private WorkerView view(Worker row) {
        return WorkerView.of(row, workerService.getWorker(row.getId()).orElse(null));
    }
}
