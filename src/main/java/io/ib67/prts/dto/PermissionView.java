package io.ib67.prts.dto;

import io.ib67.prts.Perm;

import java.util.Objects;

/**
 * Representation of a permission and its system-wide ban status.
 *
 * @param scope       Scope of the permission ({@code global} or {@code project}).
 * @param category    Grouping a picker renders as a section.
 * @param description What holding this permission lets its holder do.
 */
public record PermissionView(
        String permission, String scope, boolean banned, String category, String description) {
    public PermissionView {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(description, "description");
    }

    public static PermissionView of(Perm perm, boolean banned) {
        return new PermissionView(perm.permission(), perm.global() ? "global" : "project", banned,
                perm.category(), perm.description());
    }
}
