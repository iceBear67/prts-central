package io.ib67.prts.auth;

import io.ib67.prts.Perm;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.testing.InlineTransactions;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserService;
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

    /** @PostConstruct runs outside a request, so seed() opens its own transaction. */
    private void seed() {
        try (var ignored = new InlineTransactions()) {
            seeder.seed();
        }
    }

    /** DevAuthMechanism authenticates nobody while this is null, so it must not start out set. */
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

    /** %dev runs Hibernate with `update`, so the database outlives a restart and this must not re-register. */
    @Test
    void anExistingDevUserIsReused() {
        when(userService.findByEmail(DevAdminSeeder.EMAIL)).thenReturn(Optional.of(user));

        seed();

        verify(userService, never()).register(any(), any());
        verify(accessTokenService).issue(USER);
    }

    /** Re-granted every boot, so revoking it by hand does not quietly lock dev out. */
    @Test
    void theAdminGrantIsGlobalAndUnconditional() {
        when(userService.findByEmail(DevAdminSeeder.EMAIL)).thenReturn(Optional.of(user));

        seed();

        // A null project: ADMIN_OF_ALL is global, and PermissionService rejects a scoped grant of one.
        verify(permissionService).grant(USER, Perm.ADMIN_OF_ALL, null);
    }

    /** Rerolled rather than reused, so a token leaked from a boot log dies with that process. */
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
