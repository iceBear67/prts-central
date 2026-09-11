package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Seeds a default admin user and access token for local development when OIDC is disabled.
 */
@ApplicationScoped
@IfBuildProfile("dev")
@IfBuildProperty(name = "quarkus.oidc.enabled", stringValue = "false")
public class DevAdminSeeder {
    private static final Logger LOG = Logger.getLogger(DevAdminSeeder.class);

    /** Identity of the auto-login account, also what {@code ExampleDataSeeder} hands its projects to. */
    public static final String EMAIL = "dev@localhost";
    public static final String NAME = "dev";

    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;
    @Inject
    AccessTokenService accessTokenService;

    @Nullable
    private volatile String token;

    /** Returns the dev user's access token, or null if seeding has not run or failed. */
    @Nullable
    String token() {
        return token;
    }

    // Seeding observes StartupEvent rather than @PostConstruct: DevAuthMechanism dereferences this
    // bean's client proxy on the IO thread, and a @PostConstruct would run these queries there
    // whenever the proxy is what first creates the bean.
    void seed(@Observes StartupEvent event) {
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
