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
 * Development-only authentication mechanism that authenticates uncredentialed requests
 * as the seeded dev admin user when OIDC is disabled.
 *
 * <p>Requests with explicit credentials (e.g. Bearer tokens) pass through to the standard authentication chain.
 */
@ApplicationScoped
@IfBuildProfile("dev")
@IfBuildProperty(name = "quarkus.oidc.enabled", stringValue = "false")
public class DevAuthMechanism implements HttpAuthenticationMechanism {

    // Lower priority than AccessTokenAuthMechanism (1500) so explicit tokens take precedence.
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
        // Delegate to the access token authentication flow.
        return identityProviderManager.authenticate(new AccessTokenAuthenticationRequest(token));
    }

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

    /** Checks if the request already contains an authorization header. */
    private static boolean credentialed(RoutingContext context) {
        return context.request().getHeader(HttpHeaders.AUTHORIZATION) != null
                || context.request().getHeader(WorkerAuthMechanism.HEADER) != null;
    }
}
