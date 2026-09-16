package io.ib67.prts.dto.project;

import io.ib67.prts.dto.IssuedTokenView;
import io.ib67.prts.dto.UserInfo;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a sub-account and its granted permissions within a project.
 * Built by {@code SubAccountService.viewOf}.
 *
 * @param token The plaintext credential, present only in the response to the call that minted it —
 *              a create asking for one, or {@code PUT .../token}. Null everywhere else; nothing can
 *              read it back afterwards.
 */
public record SubAccountView(
        UUID userId,
        String name,
        List<String> permissions,
        UserInfo createdBy,
        Instant createdAt,
        @Nullable IssuedTokenView token
) {
    public SubAccountView {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

}
