package io.ib67.prts.dto;

import jakarta.annotation.Nullable;

/**
 * A partial update: a null field is left as it is, and at least one must be present.
 *
 * @param description blank clears it.
 * @param value       the new plaintext, sealed on the way in; never blank.
 */
public record UpdateSecretRequest(
        @Nullable String description,
        @Nullable String value
) {
}
