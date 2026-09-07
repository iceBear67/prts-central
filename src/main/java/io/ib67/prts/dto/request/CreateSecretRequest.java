package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;

/**
 * Request payload to create a new project secret.
 *
 * @param description Optional description for the secret.
 * @param value       Plaintext secret value to seal and store.
 */
public record CreateSecretRequest(
        String name,
        @Nullable String description,
        String value
) {
}
