package io.ib67.prts.dto;

import io.ib67.prts.secret.ProjectSecret;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * A secret as everything outside the server sees it: that it exists, what it is for, and since when.
 * There is deliberately no value field — not even the ciphertext — so no endpoint can hand one back.
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
