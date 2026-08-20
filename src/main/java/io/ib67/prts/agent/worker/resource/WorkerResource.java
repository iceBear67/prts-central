package io.ib67.prts.agent.worker.resource;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.dto.WorkerView;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/worker")
@Produces(MediaType.APPLICATION_JSON)
public class WorkerResource {
    @Inject
    WorkerService workerService;

    @GET
    public List<WorkerView> listWorkers() {
        return workerService.getActiveWorkers().entrySet().stream()
                .map(entry -> WorkerView.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public WorkerView getWorker(@PathParam("id") UUID id) {
        return workerService.getWorker(id)
                .map(worker -> WorkerView.of(id, worker))
                .orElseThrow(NotFoundException::new);
    }
}
