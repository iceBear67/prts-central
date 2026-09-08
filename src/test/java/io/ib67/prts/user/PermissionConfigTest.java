package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolves {@code permission.banned} through the real SmallRye mapping.
 *
 * <p>Nothing else covers this: the fast tiers never build a config, and the tier that boots one does not
 * run locally. An empty list has no spelling in configuration, and getting that wrong fails startup
 * rather than any test.
 */
class PermissionConfigTest {

    private static PermissionConfig configOf(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withMapping(PermissionConfig.class)
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .build()
                .getConfigMapping(PermissionConfig.class);
    }

    private static PermissionService serviceOf(Map<String, String> properties) {
        var service = new PermissionService();
        service.permissionConfig = configOf(properties);
        return service;
    }

    /** No `permission` section at all. */
    @Test
    void anAbsentPropertyResolves() {
        assertTrue(configOf(Map.of()).banned().isEmpty());
    }

    /**
     * What {@code banned: [ ]} in the YAML actually reaches SmallRye as. A plain {@code List} here fails
     * startup with SRCFG00040, which is exactly the shape this guards.
     */
    @Test
    void anEmptyListReachesUsAsTheEmptyString() {
        assertTrue(configOf(Map.of("permission.banned", "")).banned().isEmpty());
    }

    @Test
    void oneBanIsRead() {
        assertEquals(List.of("job:create"),
                configOf(Map.of("permission.banned", "job:create")).banned().orElseThrow());
    }

    @Test
    void severalBansAreSplitOnCommas() {
        assertEquals(List.of("job:create", "job:cancel"),
                configOf(Map.of("permission.banned", "job:create,job:cancel")).banned().orElseThrow());
    }

    @Test
    void nothingIsBannedByDefault() {
        var service = serviceOf(Map.of());
        service.resolveBans();

        assertFalse(service.isBanned(Perm.JOB_CREATE));
    }

    @Test
    void aConfiguredBanTakesEffect() {
        var service = serviceOf(Map.of("permission.banned", Perm.JOB_CREATE.permission()));
        service.resolveBans();

        assertTrue(service.isBanned(Perm.JOB_CREATE));
        assertFalse(service.isBanned(Perm.JOB_CANCEL));
    }

    /** A typo would otherwise be a ban nobody notices is missing. */
    @Test
    void anUnknownPermissionFailsStartup() {
        var service = serviceOf(Map.of("permission.banned", "job:teleport"));

        var thrown = assertThrows(IllegalStateException.class, service::resolveBans);
        assertTrue(thrown.getMessage().contains("job:teleport"));
    }

    @Test
    void banningAdminOfAllFailsStartup() {
        var service = serviceOf(Map.of("permission.banned", Perm.ADMIN_OF_ALL.permission()));

        var thrown = assertThrows(IllegalStateException.class, service::resolveBans);
        assertTrue(thrown.getMessage().contains(Perm.ADMIN_OF_ALL.permission()));
    }
}
