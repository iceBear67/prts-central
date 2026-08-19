package io.ib67.prts.agent.runner.resource;

import io.ib67.prts.agent.runner.RunnerService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.UUID;

@Path("/runner")
public class RunnerResource {
    @Inject
    RunnerService runnerService;


    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<UUID, RunnerService.Runner> fetchRunners() {
        return runnerService.getActiveRunners();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRunner(@PathParam("id") UUID id) {
        var runner = runnerService.getRunner(id);
        return runner.map(Response::ok).orElseGet(() -> Response.status(404)).build();
    }
}
