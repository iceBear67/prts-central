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
 * Builds the same identity an OIDC login ends up with — the {@link User} attribute above all, which
 * is what {@code UserContext.get()} and therefore every {@code @RequirePermission} reads. That
 * attribute is the whole of the equivalence: nothing downstream can tell the two apart.
 *
 * <p>{@link UserIdentityAugmenter} then leaves this identity alone, because its principal is no
 * {@code JsonWebToken} and carries no issuer.
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
        // Blocking: the lookup is a database read and this runs on the event loop. In a transaction
        // this bean opens itself, because a mechanism runs before the request context exists — there
        // is no session for either the token lookup or the admin check to join.
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
