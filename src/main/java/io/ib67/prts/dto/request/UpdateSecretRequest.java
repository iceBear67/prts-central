package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;

/**
 * Request payload to update a project secret.
 *
 * @param description New description, or empty to clear. Null keeps the existing description.
 * @param value       New plaintext secret value. Null keeps the existing secret value.
 */
public record UpdateSecretRequest(
        @Nullable String description,
        @Nullable String value
) {
}
