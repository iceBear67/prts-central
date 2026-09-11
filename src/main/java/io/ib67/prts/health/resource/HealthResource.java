package io.ib67.prts.health.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Liveness probe for infrastructure that has no credentials: a load balancer or orchestrator must be
 * able to test the process without minting a token. Registered as a public path in
 * {@code quarkus.http.auth.permission}, ahead of the {@code /api/*} authenticated policy.
 *
 * <p>It reads nothing, so a 204 proves the HTTP layer answers — not that the database or workers do.
 */
@Path("/health")
public class HealthResource {

    /** Answers to any caller, authenticated or not. */
    @GET
    public void health() {
    }
}
