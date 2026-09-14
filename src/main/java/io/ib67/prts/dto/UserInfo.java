package io.ib67.prts.dto;

import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a user referenced by another view.
 *
 * @param name The user's name, or null if the user account has been deleted.
 */
public record UserInfo(
        UUID id,
        @Nullable String name
) {
    public UserInfo {
        Objects.requireNonNull(id, "id");
    }

    /** Resolves a single user reference. Paginated views should resolve in bulk via {@link #of(UUID, User)}. */
    public static UserInfo of(UUID id) {
        return of(id, User.findById(id));
    }

    /** Creates a UserInfo with the resolved user's name, or null if absent. */
    public static UserInfo of(UUID id, @Nullable User user) {
        return new UserInfo(id, user == null ? null : user.getName());
    }
}
