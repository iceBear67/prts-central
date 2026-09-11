package io.ib67.prts.dto.project;

import io.ib67.prts.Perm;
import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.User;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a sub-account and its granted permissions within a project.
 */
public record SubAccountView(
        UUID userId,
        String name,
        List<String> permissions,
        UserInfo createdBy,
        Instant createdAt
) {
    public SubAccountView {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static SubAccountView of(SubAccount account, Collection<Perm> permissions, Map<UUID, User> users) {
        return new SubAccountView(
                account.getUserId(),
                account.getUser().getName(),
                permissions.stream().map(Perm::permission).sorted().toList(),
                UserInfo.of(account.getCreatedBy(), users),
                account.getCreatedAt());
    }
}
