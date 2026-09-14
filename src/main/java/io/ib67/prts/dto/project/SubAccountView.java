package io.ib67.prts.dto.project;

import io.ib67.prts.dto.UserInfo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a sub-account and its granted permissions within a project.
 * Built by {@code SubAccountService.viewOf}.
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

}
