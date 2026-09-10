package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.testing.InlineTransactions;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserService;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevAdminSeederTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final AccessTokenService accessTokenService = mock(AccessTokenService.class);

    private final DevAdminSeeder seeder = new DevAdminSeeder();

    private final User user = User.builder().id(USER).name(DevAdminSeeder.NAME)
            .email(DevAdminSeeder.EMAIL).build();

    @BeforeEach
    void setUp() {
        seeder.userService = userService;
        seeder.permissionService = permissionService;
        seeder.accessTokenService = accessTokenService;
        when(userService.register(any(), any())).thenReturn(user);
        issues("prts_first");
    }

    private void issues(String token) {
        when(accessTokenService.issue(USER))
                .thenReturn(new AccessTokenService.Issued(token, Instant.EPOCH));
    }

    // Execute seed() within an inline transaction.
    private void seed() {
        try (var ignored = new InlineTransactions()) {
            seeder.seed(new StartupEvent());
        }
    }

    /** Token is null before seeding has executed. */
    @Test
    void thereIsNoTokenBeforeSeeding() {
        assertNull(seeder.token());
    }

    @Test
    void anEmptyDatabaseGetsADevUser() {
        when(userService.findByEmail(DevAdminSeeder.EMAIL)).thenReturn(Optional.empty());

        seed();

        verify(userService).register(DevAdminSeeder.NAME, DevAdminSeeder.EMAIL);
        assertEquals("prts_first", seeder.token());
    }

    /** Existing dev user accounts are reused across restarts without re-registering. */
    @Test
    void anExistingDevUserIsReused() {
        when(userService.findByEmail(DevAdminSeeder.EMAIL)).thenReturn(Optional.of(user));

        seed();

        verify(userService, never()).register(any(), any());
        verify(accessTokenService).issue(USER);
    }

    /** Ensures admin permissions are granted globally to the dev user on startup. */
    @Test
    void theAdminGrantIsGlobalAndUnconditional() {
        when(userService.findByEmail(DevAdminSeeder.EMAIL)).thenReturn(Optional.of(user));

        seed();

        verify(permissionService).grant(USER, Perm.ADMIN_OF_ALL, null);
    }

    /** A new token is issued on each startup. */
    @Test
    void theTokenIsRerolledEveryBoot() {
        when(userService.findByEmail(DevAdminSeeder.EMAIL)).thenReturn(Optional.of(user));

        seed();
        issues("prts_second");
        seed();

        assertEquals("prts_second", seeder.token());
        verify(accessTokenService, times(2)).issue(USER);
    }
}
