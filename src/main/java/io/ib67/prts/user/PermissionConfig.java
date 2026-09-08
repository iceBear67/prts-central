package io.ib67.prts.user;

import io.smallrye.config.ConfigMapping;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "permission")
public interface PermissionConfig {

    /**
     * Identifiers of permissions disabled system-wide (see {@link io.ib67.prts.Perm#permission()}).
     *
     * <p>Wrapped in {@link Optional} because SmallRye Config maps an empty YAML list ({@code banned: []})
     * to null or an empty string, which causes conversion errors if typed directly as {@code List<String>}.
     * {@link PermissionService} normalizes absent values to an empty set during initialization.
     *
     * <p>Configuring {@code admin:all} or an unknown permission identifier will cause application startup to fail.
     */
    Optional<List<String>> banned();
}
