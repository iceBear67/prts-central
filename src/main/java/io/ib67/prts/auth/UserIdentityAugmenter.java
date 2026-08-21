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
 * Resolves an OIDC login to a local {@link User}, registering one the first time that
 * {@code (issuer, subject)} is seen. A login that cannot be registered — no {@code email} claim — is
 * left unaugmented rather than half-provisioned: it reaches endpoints with no local user attached and
 * is refused there.
 *
 * <p>A new identity always gets a new user, even when some existing user has the same address.
 * Linking two providers to one account is deliberate work ({@code UserService.linkIdentity}), because
 * doing it automatically would let a provider that does not verify addresses take over an account.
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

    /** Looked up on every request, so registration is only attempted when the lookup comes up empty. */
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
            // Two requests of the same first login race for the (issuer, subject) key; whichever loses
            // reads back the row the winner wrote instead of failing the request.
            var registered = userService.findByIssuerAndSubject(issuer, subject);
            if (registered.isEmpty()) {
                LOG.errorf(e, "cannot register %s from %s", subject, issuer);
            }
            return registered;
        }
    }

    /**
     * The issuer the token itself declares. {@code getAttribute("issuer")} is not something
     * quarkus-oidc populates, so it is only kept as a fallback.
     */
    @Nullable
    private static String issuer(SecurityIdentity identity) {
        if (identity.getPrincipal() instanceof JsonWebToken token && token.getIssuer() != null) {
            return token.getIssuer();
        }
        return identity.getAttribute("issuer");
    }

    /**
     * {@code sub} rather than the principal name: the principal name may be a username or display
     * name, which the provider lets its users change, and this is half of a primary key.
     */
    @Nullable
    private static String subject(SecurityIdentity identity) {
        if (identity.getPrincipal() instanceof JsonWebToken token && token.getSubject() != null) {
            return token.getSubject();
        }
        return identity.getPrincipal().getName();
    }

    /** First of {@code names} that carries text. */
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
            // Scalars arrive as String; a JSON-P value would otherwise stringify with its quotes.
            var value = raw instanceof JsonString json ? json.getString() : raw.toString();
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
