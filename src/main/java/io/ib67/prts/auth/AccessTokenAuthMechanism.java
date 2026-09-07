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
 * Authenticates bearer access tokens starting with {@code prts_}.
 * Requests without this token prefix are delegated to the OIDC flow.
 */
@ApplicationScoped
public class AccessTokenAuthMechanism implements HttpAuthenticationMechanism {

    private static final String BEARER = "Bearer ";

    // Above quarkus-oidc's 1001, so a personal access token is decided here and never answered with
    // a browser redirect. Package-private: DevAuthMechanism must sort below it.
    static final int PRIORITY = 1500;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context,
                                              IdentityProviderManager identityProviderManager) {
        var token = tokenOf(context);
        if (token == null) {
            return Uni.createFrom().nullItem();
        }
        context.put(HttpAuthenticationMechanism.class.getName(), this);
        return identityProviderManager.authenticate(new AccessTokenAuthenticationRequest(token));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return tokenOf(context) == null
                ? Uni.createFrom().nullItem()
                : Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer"));
    }

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

    /** Extracts the bearer token if it matches our access token prefix. */
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
