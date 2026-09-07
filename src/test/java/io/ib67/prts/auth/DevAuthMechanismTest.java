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

    /** Auto-login must never override a credential someone deliberately sent, sound or not. */
    @Test
    void aPresentedCredentialIsLeftToTheRealChain() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer prts_somebodyelse");
        assertNull(authenticate());

        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer garbage");
        assertNull(authenticate());

        verifyNoInteractions(identityProviders);
    }

    /** A worker's own credential likewise, so nothing on that path is silently upgraded to admin. */
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

    /** Under AccessTokenAuthMechanism's 1500, so a presented token is the one that decides. */
    @Test
    void itYieldsToTheAccessTokenMechanism() {
        assertTrue(mechanism.getPriority() < 1500, "priority: " + mechanism.getPriority());
    }

    /** It asks for no credential, so it has nothing to challenge for. */
    @Test
    void itChallengesForNothing() {
        assertNull(mechanism.getChallenge(context).await().indefinitely());
    }

    /** Quarkus validates at startup that a provider exists for every type a mechanism delegates. */
    @Test
    void itDeclaresTheTypeItDelegates() {
        assertTrue(mechanism.getCredentialTypes().contains(AccessTokenAuthenticationRequest.class));
    }

    /** ChallengeSender looks the mechanism back up from the context. */
    @Test
    void theMechanismRecordsItselfOnTheContext() {
        authenticate();

        verify(context).put(HttpAuthenticationMechanism.class.getName(), mechanism);
    }
}
