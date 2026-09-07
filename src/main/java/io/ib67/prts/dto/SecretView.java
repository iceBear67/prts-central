package io.ib67.prts.dto;

import io.ib67.prts.secret.ProjectSecret;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata view of a project secret, omitting the secret value.
 */
public record SecretView(
        String name,
        @Nullable String description,
        Instant createdAt
) {
    public SecretView {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static SecretView of(ProjectSecret secret) {
        return new SecretView(secret.getName(), secret.getDescription(), secret.getCreatedAt());
    }
}
