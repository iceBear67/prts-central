package io.ib67.prts.auth;

import io.ib67.prts.Perms;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserService;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserIdentityAugmenter implements SecurityIdentityAugmentor {
    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        var issuer = (String) identity.getAttribute("issuer");
        var subject = identity.getPrincipal().getName();
        if (issuer == null || subject == null) return Uni.createFrom().item(identity);
        return context.runBlocking(() -> userService.findByIssuerAndSubject(issuer, subject).map(user ->
                (SecurityIdentity) QuarkusSecurityIdentity.builder(identity)
                        .addRole(permissionService.has(user.getId(), Perms.ADMIN_OF_ALL) ? "admin" : "user")
                        .addAttribute(User.class.getName(), user)
                        .build()
        ).orElse(identity));
    }
}
