package io.ib67.prts.dto.admin;

import io.ib67.prts.Perm;

import java.util.Objects;

/**
 * A permission and whether it is currently switched off system-wide.
 *
 * @param scope {@code global} or {@code project}, matching what a grant of it must be scoped to.
 */
public record PermissionView(String permission, String scope, boolean banned) {
    public PermissionView {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(scope, "scope");
    }

    public static PermissionView of(Perm perm, boolean banned) {
        return new PermissionView(perm.permission(), perm.global() ? "global" : "project", banned);
    }
}
