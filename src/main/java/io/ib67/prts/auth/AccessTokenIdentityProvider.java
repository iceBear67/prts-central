package io.ib67.prts.auth;

import io.agroal.api.security.NamePrincipal;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Authenticates personal access tokens and produces the corresponding {@link SecurityIdentity}
 * with attached {@link User} details.
 */
@ApplicationScoped
public class AccessTokenIdentityProvider implements IdentityProvider<AccessTokenAuthenticationRequest> {

    @Inject
    AccessTokenService accessTokenService;
    @Inject
    PermissionService permissionService;

    @Override
    public Class<AccessTokenAuthenticationRequest> getRequestType() {
        return AccessTokenAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(AccessTokenAuthenticationRequest request,
                                              AuthenticationRequestContext context) {
        // Runs database queries in a worker thread and transaction before the JAX-RS request context is active.
        return context.runBlocking(() -> QuarkusTransaction.requiringNew()
                .call(() -> accessTokenService.resolve(request.getToken())
                        .map(this::identityOf)
                        .orElseThrow(() -> new AuthenticationFailedException("unknown access token"))));
    }

    private SecurityIdentity identityOf(User user) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new NamePrincipal(user.getId().toString()))
                .addRole(permissionService.isAdmin(user.getId()) ? "admin" : "user")
                .addAttribute(User.class.getName(), user)
                .build();
    }
}
