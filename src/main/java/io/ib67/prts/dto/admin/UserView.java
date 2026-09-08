package io.ib67.prts.dto.admin;

import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative view of an account.
 *
 * @param subAccountOf The ID of the owning project if this account is a sub-account, or null for standard users.
 */
public record UserView(
        UUID id,
        String name,
        String email,
        Instant createdAt,
        @Nullable UUID subAccountOf
) {
    public UserView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static UserView of(User user, @Nullable UUID subAccountOf) {
        return new UserView(
                user.getId(), user.getName(), user.getEmail(), user.getCreatedAt(), subAccountOf);
    }
}
