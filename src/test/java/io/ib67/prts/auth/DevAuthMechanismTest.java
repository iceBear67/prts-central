package io.ib67.prts.auth;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevAuthMechanismTest {

    private static final String TOKEN = "prts_DEVDEVDEV";

    private final SecurityIdentity identity = mock(SecurityIdentity.class);
    private final IdentityProviderManager identityProviders = mock(IdentityProviderManager.class);
    private final HttpServerRequest request = mock(HttpServerRequest.class);
    private final RoutingContext context = mock(RoutingContext.class);
    private final DevAdminSeeder seeder = mock(DevAdminSeeder.class);

    private final DevAuthMechanism mechanism = new DevAuthMechanism();

    @BeforeEach
    void setUp() {
        mechanism.seeder = seeder;
        when(seeder.token()).thenReturn(TOKEN);
        when(context.request()).thenReturn(request);
        when(identityProviders.authenticate(any(AuthenticationRequest.class)))
                .thenReturn(Uni.createFrom().item(identity));
    }

    @Nullable
    private SecurityIdentity authenticate() {
        return mechanism.authenticate(context, identityProviders).await().indefinitely();
    }

    @Test
    void anUncredentialedRequestBecomesTheDevUser() {
        assertSame(identity, authenticate());

        var captor = ArgumentCaptor.forClass(AuthenticationRequest.class);
        verify(identityProviders).authenticate(captor.capture());
        assertEquals(TOKEN, ((AccessTokenAuthenticationRequest) captor.getValue()).getToken());
    }

    /** Explicit credentials take precedence over dev auto-login. */
    @Test
    void aPresentedCredentialIsLeftToTheRealChain() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer prts_somebodyelse");
        assertNull(authenticate());

        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer garbage");
        assertNull(authenticate());

        verifyNoInteractions(identityProviders);
    }

    /** Worker tokens take precedence over dev auto-login. */
    @Test
    void aWorkerTokenIsLeftToTheRealChain() {
        when(request.getHeader(WorkerAuthMechanism.HEADER)).thenReturn("allo");

        assertNull(authenticate());
        verifyNoInteractions(identityProviders);
    }

    @Test
    void withoutASeededTokenItAuthenticatesNobody() {
        when(seeder.token()).thenReturn(null);

        assertNull(authenticate());
        verifyNoInteractions(identityProviders);
    }

    /** DevAuthMechanism priority is lower than AccessTokenAuthMechanism. */
    @Test
    void itYieldsToTheAccessTokenMechanism() {
        assertTrue(mechanism.getPriority() < AccessTokenAuthMechanism.PRIORITY,
                "priority: " + mechanism.getPriority());
    }

    @Test
    void itChallengesForNothing() {
        assertNull(mechanism.getChallenge(context).await().indefinitely());
    }

    @Test
    void itDeclaresTheTypeItDelegates() {
        assertTrue(mechanism.getCredentialTypes().contains(AccessTokenAuthenticationRequest.class));
    }

    @Test
    void theMechanismRecordsItselfOnTheContext() {
        authenticate();

        verify(context).put(HttpAuthenticationMechanism.class.getName(), mechanism);
    }
}
