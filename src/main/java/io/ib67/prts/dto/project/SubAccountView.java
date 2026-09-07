package io.ib67.prts.dto.project;

import io.ib67.prts.Perm;
import io.ib67.prts.user.SubAccount;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A sub-account and what it may do. No address and no role, because it has neither; the permissions
 * are the {@link Perm#permission()} strings, which is what a caller sets them with.
 */
public record SubAccountView(
        UUID userId,
        String name,
        List<String> permissions,
        UUID createdBy,
        Instant createdAt
) {
    public SubAccountView {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Reads {@code account.getUser()}, so the row must have been fetched with its user. */
    public static SubAccountView of(SubAccount account, Collection<Perm> permissions) {
        return new SubAccountView(
                account.getUserId(),
                account.getUser().getName(),
                permissions.stream().map(Perm::permission).sorted().toList(),
                account.getCreatedBy(),
                account.getCreatedAt());
    }
}
