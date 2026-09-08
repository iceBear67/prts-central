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
 * Seeds a default admin user and access token for local development when OIDC is disabled.
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

    /** Returns the dev user's access token, or null if seeding failed. */
    @Nullable
    String token() {
        return token;
    }

    @PostConstruct
    void seed() {
        // Open a transaction manually during application startup.
        var issued = QuarkusTransaction.requiringNew().call(() -> {
            var user = userService.findByEmail(EMAIL)
                    .orElseGet(() -> userService.register(NAME, EMAIL));
            permissionService.grant(user.getId(), Perm.ADMIN_OF_ALL, null);
            return accessTokenService.issue(user.getId());
        });
        token = issued.token();
        LOG.infof("dev auto-login is on: uncredentialed requests are %s <%s>, who holds %s."
                + " Its token, for clients that want one: %s", NAME, EMAIL, Perm.ADMIN_OF_ALL.permission(), token);
    }
}
