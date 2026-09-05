package io.ib67.prts.auth;

import io.ib67.prts.secret.user.AccessTokenService;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * Authenticates {@code Authorization: Bearer prts_…}. A request without one is declined, so the OIDC
 * flow and its browser sessions are untouched.
 */
@ApplicationScoped
public class AccessTokenAuthMechanism implements HttpAuthenticationMechanism {

    private static final String BEARER = "Bearer ";

    /**
     * Above quarkus-oidc's 1001 — mechanisms are tried in descending priority, and a bad token has to
     * answer 401 rather than be handed to OIDC, which would answer a login redirect.
     */
    private static final int PRIORITY = 1500;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context,
                                              IdentityProviderManager identityProviderManager) {
        var token = tokenOf(context);
        if (token == null) {
            return Uni.createFrom().nullItem();
        }
        // What HttpAuthenticator reads back to decide who owns the challenge for this request.
        context.put(HttpAuthenticationMechanism.class.getName(), this);
        return identityProviderManager.authenticate(new AccessTokenAuthenticationRequest(token));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return tokenOf(context) == null
                // Nothing of ours was presented, so let OIDC send its redirect instead.
                ? Uni.createFrom().nullItem()
                : Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer"));
    }

    /**
     * Declaring it makes Quarkus fail at startup unless {@link AccessTokenIdentityProvider} is
     * installed, which turns a wiring mistake into a build failure instead of a 401 in production.
     */
    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(AccessTokenAuthenticationRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(
                HttpCredentialTransport.Type.AUTHORIZATION,
                "bearer",
                "access-token"
        ));
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    /** The token only if it is one of ours; another scheme's bearer is not ours to fail. */
    @Nullable
    private static String tokenOf(RoutingContext context) {
        var header = context.request().getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        var token = header.substring(BEARER.length()).trim();
        return token.startsWith(AccessTokenService.PREFIX) ? token : null;
    }
}
