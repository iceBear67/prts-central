package io.ib67.prts.dto;

import jakarta.annotation.Nullable;

/**
 * @param description optional; what the name is for, so a member can use it without seeing it
 * @param value       the only place a plaintext secret travels over the API, and only inbound.
 */
public record CreateSecretRequest(
        String name,
        @Nullable String description,
        String value
) {
}
