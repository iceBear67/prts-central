package io.ib67.prts.dto;

import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a user referenced by another view.
 *
 * @param name The user's name, or null if the referenced account no longer exists — the referencing
 *             rows (jobs, tasks, sub-accounts) carry no foreign key and outlive their creator, so a
 *             dangling reference is rendered rather than rejected.
 */
public record UserInfo(
        UUID id,
        @Nullable String name
) {
    public UserInfo {
        Objects.requireNonNull(id, "id");
    }

    /** Resolves a lone reference; a listing resolves its whole page and uses {@link #of(UUID, User)}. */
    public static UserInfo of(UUID id) {
        return of(id, User.findById(id));
    }

    /** Builds the view for an ID against the account it resolved to, leaving a gap nameless. */
    public static UserInfo of(UUID id, @Nullable User user) {
        return new UserInfo(id, user == null ? null : user.getName());
    }
}
