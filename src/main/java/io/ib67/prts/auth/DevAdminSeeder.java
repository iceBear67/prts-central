package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Dev only: mints an {@link Perm#ADMIN_OF_ALL} user and the access token {@link DevAuthMechanism}
 * logs uncredentialed requests in as.
 *
 * <p>Both conditions are build-time, so neither this nor the mechanism is built into {@code %prod} at
 * all — a guarantee no runtime switch could give. {@code %dev} pins OIDC off, so the second only bites
 * when someone turns it back on to debug the real login, which is when a stray admin would be in the way.
 */
@Startup
@ApplicationScoped
@IfBuildProfile("dev")
@IfBuildProperty(name = "quarkus.oidc.enabled", stringValue = "false")
public class DevAdminSeeder {
    private static final Logger LOG = Logger.getLogger(DevAdminSeeder.class);

    static final String EMAIL = "dev@localhost";
    static final String NAME = "dev";

    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;
    @Inject
    AccessTokenService accessTokenService;

    @Nullable
    private volatile String token;

    /** The dev user's token plaintext, or null if seeding failed. */
    @Nullable
    String token() {
        return token;
    }

    @PostConstruct
    void seed() {
        // Startup runs outside any request, so the session has to be opened by hand; the @Transactional
        // collaborators join this one.
        var issued = QuarkusTransaction.requiringNew().call(() -> {
            var user = userService.findByEmail(EMAIL)
                    .orElseGet(() -> userService.register(NAME, EMAIL));
            permissionService.grant(user.getId(), Perm.ADMIN_OF_ALL, null);
            // Rerolled every boot: nothing outside this process has to survive a restart.
            return accessTokenService.issue(user.getId());
        });
        token = issued.token();
        LOG.infof("dev auto-login is on: uncredentialed requests are %s <%s>, who holds %s."
                + " Its token, for clients that want one: %s", NAME, EMAIL, Perm.ADMIN_OF_ALL.permission(), token);
    }
}
