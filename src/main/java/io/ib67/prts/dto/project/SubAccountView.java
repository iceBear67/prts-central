package io.ib67.prts.dto.project;

import io.ib67.prts.Perm;
import io.ib67.prts.user.SubAccount;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a sub-account and its granted permissions within a project.
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

    public static SubAccountView of(SubAccount account, Collection<Perm> permissions) {
        return new SubAccountView(
                account.getUserId(),
                account.getUser().getName(),
                permissions.stream().map(Perm::permission).sorted().toList(),
                account.getCreatedBy(),
                account.getCreatedAt());
    }
}
