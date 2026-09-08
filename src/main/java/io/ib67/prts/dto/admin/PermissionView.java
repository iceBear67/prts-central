package io.ib67.prts.dto.admin;

import io.ib67.prts.Perm;

import java.util.Objects;

/**
 * Representation of a permission and its system-wide ban status.
 *
 * @param scope Scope of the permission ({@code global} or {@code project}).
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
