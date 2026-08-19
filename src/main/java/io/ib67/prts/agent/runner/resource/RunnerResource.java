package io.ib67.prts.agent.runner.resource;

import io.ib67.prts.agent.runner.RunnerService;
import io.ib67.prts.dto.RunnerView;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/runner")
@Produces(MediaType.APPLICATION_JSON)
public class RunnerResource {
    @Inject
    RunnerService runnerService;

    @GET
    public List<RunnerView> listRunners() {
        return runnerService.getActiveRunners().entrySet().stream()
                .map(entry -> RunnerView.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public RunnerView getRunner(@PathParam("id") UUID id) {
        return runnerService.getRunner(id)
                .map(runner -> RunnerView.of(id, runner))
                .orElseThrow(NotFoundException::new);
    }
}
