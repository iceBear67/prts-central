package io.ib67.prts.user;

import io.smallrye.config.ConfigMapping;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "permission")
public interface PermissionConfig {

    /**
     * Permissions taken out of service system-wide, by their {@link io.ib67.prts.Perm#permission()}
     * identifier. Refused to everyone, {@code admin:all} holders included.
     *
     * <p>{@code Optional} against the usual rule about containers, and not by choice: an empty list has
     * no spelling in configuration — {@code banned: [ ]} reaches SmallRye as the empty string, which its
     * collection converter reads as null. A plain {@code List} then fails startup with SRCFG00040
     * instead of resolving to no bans, whether the emptiness came from the file or from a
     * {@code @WithDefault("")}. {@link PermissionService} normalizes it away at once, so nothing
     * downstream has to tell absent from empty.
     *
     * <p>An unknown identifier, or {@code admin:all} itself, fails startup.
     */
    Optional<List<String>> banned();
}
