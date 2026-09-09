package io.ib67.prts.dto;

import io.ib67.prts.user.Permission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A user's permission identifiers, split into the system-wide ones and those scoped to a project.
 *
 * <p>Not returned as it stands: the views built from it flatten or merge the two halves.
 */
public record ScopedGrants(List<String> global, Map<UUID, List<String>> byProject) {
    public ScopedGrants {
        Objects.requireNonNull(global, "global");
        Objects.requireNonNull(byProject, "byProject");
    }

    /** Groups grants by their project, sorted, lifting the {@link Permission#GLOBAL} scope out. */
    public static ScopedGrants of(Collection<Permission.Id> grants) {
        var grouped = new TreeMap<UUID, List<String>>();
        grants.forEach(grant -> grouped
                .computeIfAbsent(grant.getProjectId(), scope -> new ArrayList<>())
                .add(grant.getPermission()));
        grouped.values().forEach(Collections::sort);
        var global = grouped.remove(Permission.GLOBAL);
        return new ScopedGrants(global == null ? List.of() : global, grouped);
    }
}
