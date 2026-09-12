package io.ib67.prts.dto.project;

import io.ib67.prts.Perm;
import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.User;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

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

    /** Builds the view for a lone sub-account, resolving its creator. */
    public static SubAccountView of(SubAccount account, Collection<Perm> permissions) {
        return of(account, permissions, UserInfo.of(account.getCreatedBy()));
    }

    /** Builds the views for a listing, resolving the whole page's creators in one query. */
    public static List<SubAccountView> of(
            List<SubAccount> accounts, Function<SubAccount, Collection<Perm>> permissions) {
        var users = User.mapByIds(accounts.stream().map(SubAccount::getCreatedBy).distinct().toList());
        return accounts.stream()
                .map(account -> of(
                        account,
                        permissions.apply(account),
                        UserInfo.of(account.getCreatedBy(), users.get(account.getCreatedBy()))))
                .toList();
    }

    private static SubAccountView of(SubAccount account, Collection<Perm> permissions, UserInfo createdBy) {
        return new SubAccountView(
                account.getUserId(),
                account.getUser().getName(),
                permissions.stream().map(Perm::permission).sorted().toList(),
                createdBy,
                account.getCreatedAt());
    }
}
