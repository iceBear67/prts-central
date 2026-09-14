package io.ib67.prts.health.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Unauthenticated liveness endpoint for infrastructure probes (e.g. load balancers).
 *
 * <p>Returns 204 No Content to verify HTTP server responsiveness without probing database or workers.
 */
@Path("/health")
public class HealthResource {

    /** Returns 204 No Content. */
    @GET
    public void health() {
    }
}
