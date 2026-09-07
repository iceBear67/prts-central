package io.ib67.prts.auth;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Dev only: authenticates every request that presents no credential of its own as the
 * {@link DevAdminSeeder} user, so {@code %dev} needs no OIDC provider and no client secret in the
 * repository. Not built into {@code %prod}; see {@link DevAdminSeeder}.
 *
 * <p>A request that does present one is left to the real chain, so a personal access token — and the
 * 401 a bad one earns — stays exercisable without switching profiles.
 *
 * <p>Auto-login is a substitute for a provider, not a companion to one: switch OIDC back on and this
 * bean is not built, or it would answer every browser request before the login redirect could.
 */
@ApplicationScoped
@IfBuildProfile("dev")
@IfBuildProperty(name = "quarkus.oidc.enabled", stringValue = "false")
public class DevAuthMechanism implements HttpAuthenticationMechanism {

    // Below AccessTokenAuthMechanism.PRIORITY: a presented token is the one that decides.
    private static final int PRIORITY = 1200;

    @Inject
    DevAdminSeeder seeder;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context,
                                              IdentityProviderManager identityProviderManager) {
        var token = seeder.token();
        if (token == null || credentialed(context)) {
            return Uni.createFrom().nullItem();
        }
        context.put(HttpAuthenticationMechanism.class.getName(), this);
        // Going through the token chain rather than building an identity here keeps the two
        // indistinguishable, and inherits its runBlocking so the lookup stays off the event loop.
        return identityProviderManager.authenticate(new AccessTokenAuthenticationRequest(token));
    }

    /** Nothing to challenge for: this mechanism asks for no credential. */
    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(AccessTokenAuthenticationRequest.class);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    /** Whether the request presents a credential of its own, sound or not. */
    private static boolean credentialed(RoutingContext context) {
        return context.request().getHeader(HttpHeaders.AUTHORIZATION) != null
                || context.request().getHeader(WorkerAuthMechanism.HEADER) != null;
    }
}
