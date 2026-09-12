package io.ib67.prts.dto;

import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.util.Map;
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

    /** Builds the view for an ID against the users a listing resolved, leaving a gap nameless. */
    public static UserInfo of(UUID id, Map<UUID, User> users) {
        var user = users.get(id);
        return new UserInfo(id, user == null ? null : user.getName());
    }
}
