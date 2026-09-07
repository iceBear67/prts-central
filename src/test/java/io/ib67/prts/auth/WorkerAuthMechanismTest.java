package io.ib67.prts.auth;

import io.ib67.prts.agent.worker.WorkerConfig;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerAuthMechanismTest {

    // The literal on purpose, not WorkerAuthMechanism.HEADER: workers send this exact name, so a change
    // to the constant should fail here rather than follow along.
    private static final String HEADER = "X-Worker-Token";
    private static final String SECRET = "correct-horse-battery-staple";

    private final WorkerConfig workerConfig = mock(WorkerConfig.class);
    private final HttpServerRequest request = mock(HttpServerRequest.class);
    private final RoutingContext context = mock(RoutingContext.class);

    private final WorkerAuthMechanism mechanism = new WorkerAuthMechanism();

    @BeforeEach
    void setUp() {
        mechanism.workerConfig = workerConfig;
        when(workerConfig.secret()).thenReturn(SECRET);
        when(context.request()).thenReturn(request);
    }

    @Nullable
    private SecurityIdentity authenticate(@Nullable String token) {
        when(request.getHeader(HEADER)).thenReturn(token);
        return mechanism.authenticate(context, mock(IdentityProviderManager.class))
                .await().indefinitely();
    }

    /** A null identity means "not mine" and lets the next mechanism try, rather than failing the request. */
    @Test
    void aRequestWithoutTheHeaderIsNotOurs() {
        assertNull(authenticate(null));
    }

    @Test
    void aWrongTokenIsRejected() {
        assertNull(authenticate("wrong"));
        assertNull(authenticate(""));
        assertNull(authenticate(SECRET + "x"));
        assertNull(authenticate(SECRET.substring(0, SECRET.length() - 1)));
        assertNull(authenticate(SECRET.toUpperCase()));
    }

    @Test
    void theSharedSecretAuthenticatesAWorker() {
        var identity = authenticate(SECRET);

        assertNotNull(identity);
        assertTrue(identity.hasRole("worker"));
        assertEquals("some-worker", identity.getPrincipal().getName());
    }

    @Test
    void theChallengeIsABare401() {
        var challenge = mechanism.getChallenge(context).await().indefinitely();

        assertEquals(401, challenge.status);
        assertEquals(0, challenge.getHeaders().size());
    }

    /** application.yml names this scheme in the worker_ws permission policy. */
    @Test
    void theSchemeIsNamedWorkerToken() {
        var transport = mechanism.getCredentialTransport(context).await().indefinitely();

        assertEquals("worker-token", transport.getAuthenticationScheme());
    }
}
