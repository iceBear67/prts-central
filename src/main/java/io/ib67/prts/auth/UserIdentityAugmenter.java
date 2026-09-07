package io.ib67.prts.auth;

import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserService;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Augments OIDC identities with local {@link User} entities, auto-provisioning new users on first login.
 */
@ApplicationScoped
public class UserIdentityAugmenter implements SecurityIdentityAugmentor {
    private static final Logger LOG = Logger.getLogger(UserIdentityAugmenter.class);

    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        var issuer = issuer(identity);
        var subject = subject(identity);
        if (issuer == null || subject == null) {
            return Uni.createFrom().item(identity);
        }
        return context.runBlocking(() -> resolve(identity, issuer, subject)
                .map(user -> (SecurityIdentity) QuarkusSecurityIdentity.builder(identity)
                        .addRole(permissionService.isAdmin(user.getId()) ? "admin" : "user")
                        .addAttribute(User.class.getName(), user)
                        .build())
                .orElse(identity));
    }

    private Optional<User> resolve(SecurityIdentity identity, String issuer, String subject) {
        var existing = userService.findByIssuerAndSubject(issuer, subject);
        if (existing.isPresent()) {
            return existing;
        }
        var email = claim(identity, "email");
        if (email == null) {
            LOG.warnf("not registering %s from %s: the token carries no email claim", subject, issuer);
            return Optional.empty();
        }
        var name = Objects.requireNonNullElse(claim(identity, "name", "preferred_username"), subject);
        try {
            return Optional.of(userService.provision(issuer, subject, name, email));
        } catch (RuntimeException e) {
            // Handle concurrent first-login registration races gracefully.
            var registered = userService.findByIssuerAndSubject(issuer, subject);
            if (registered.isEmpty()) {
                LOG.errorf(e, "cannot register %s from %s", subject, issuer);
            }
            return registered;
        }
    }

    @Nullable
    private static String issuer(SecurityIdentity identity) {
        if (identity.getPrincipal() instanceof JsonWebToken token && token.getIssuer() != null) {
            return token.getIssuer();
        }
        return identity.getAttribute("issuer");
    }

    @Nullable
    private static String subject(SecurityIdentity identity) {
        if (identity.getPrincipal() instanceof JsonWebToken token && token.getSubject() != null) {
            return token.getSubject();
        }
        return identity.getPrincipal().getName();
    }

    /** Returns the first non-blank claim value matching any of the specified claim names. */
    @Nullable
    private static String claim(SecurityIdentity identity, String... names) {
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            return null;
        }
        for (var name : names) {
            Object raw = token.getClaim(name);
            if (raw == null) {
                continue;
            }
            var value = raw instanceof JsonString json ? json.getString() : raw.toString();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
