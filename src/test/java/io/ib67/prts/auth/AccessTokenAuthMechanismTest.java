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

class AccessTokenAuthMechanismTest {

    private static final String TOKEN = "prts_AAAABBBBCCCC";

    private final SecurityIdentity identity = mock(SecurityIdentity.class);
    private final IdentityProviderManager identityProviders = mock(IdentityProviderManager.class);
    private final HttpServerRequest request = mock(HttpServerRequest.class);
    private final RoutingContext context = mock(RoutingContext.class);

    private final AccessTokenAuthMechanism mechanism = new AccessTokenAuthMechanism();

    @BeforeEach
    void setUp() {
        when(context.request()).thenReturn(request);
        when(identityProviders.authenticate(any(AuthenticationRequest.class)))
                .thenReturn(Uni.createFrom().item(identity));
    }

    @Nullable
    private SecurityIdentity authenticate(@Nullable String authorization) {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(authorization);
        return mechanism.authenticate(context, identityProviders).await().indefinitely();
    }

    /** A null identity hands the request to the next mechanism, which is how OIDC still gets its turn. */
    @Test
    void aRequestWithoutOurTokenIsNotOurs() {
        assertNull(authenticate(null));
        assertNull(authenticate("Basic dXNlcjpwYXNz"));
        assertNull(authenticate("Bearer ghp_something"));
        assertNull(authenticate("Bearer "));
        assertNull(authenticate("prts_AAAABBBBCCCC"));
        verifyNoInteractions(identityProviders);
    }

    @Test
    void ourTokenGoesToTheIdentityProvider() {
        assertSame(identity, authenticate("Bearer " + TOKEN));

        var captor = ArgumentCaptor.forClass(AuthenticationRequest.class);
        verify(identityProviders).authenticate(captor.capture());
        assertEquals(TOKEN, ((AccessTokenAuthenticationRequest) captor.getValue()).getToken());
    }

    @Test
    void theBearerPrefixIsCaseInsensitive() {
        assertSame(identity, authenticate("bearer " + TOKEN));
        assertSame(identity, authenticate("BEARER " + TOKEN));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertSame(identity, authenticate("Bearer   " + TOKEN + "  "));

        var captor = ArgumentCaptor.forClass(AuthenticationRequest.class);
        verify(identityProviders).authenticate(captor.capture());
        assertEquals(TOKEN, ((AccessTokenAuthenticationRequest) captor.getValue()).getToken());
    }

    /** ChallengeSender looks the mechanism back up from the context to build the 401. */
    @Test
    void theMechanismRecordsItselfOnTheContext() {
        authenticate("Bearer " + TOKEN);

        verify(context).put(HttpAuthenticationMechanism.class.getName(), mechanism);
    }

    @Test
    void onlyOurTokenGetsA401ChallengeRatherThanAnOidcRedirect() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + TOKEN);
        var challenge = mechanism.getChallenge(context).await().indefinitely();

        assertEquals(401, challenge.status);
        assertEquals("Bearer", challenge.getHeaders().get("WWW-Authenticate"));
    }

    @Test
    void aForeignTokenLeavesTheChallengeToOidc() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer ghp_something");

        assertNull(mechanism.getChallenge(context).await().indefinitely());
    }

    /** Must outrank quarkus-oidc (1001), or a browser redirect would win over a personal access token. */
    @Test
    void theMechanismOutranksOidc() {
        assertTrue(mechanism.getPriority() > 1001, "priority: " + mechanism.getPriority());
    }

    @Test
    void itDeclaresItsCredentialType() {
        assertTrue(mechanism.getCredentialTypes().contains(AccessTokenAuthenticationRequest.class));
        assertEquals("access-token",
                mechanism.getCredentialTransport(context).await().indefinitely().getAuthenticationScheme());
    }
}
