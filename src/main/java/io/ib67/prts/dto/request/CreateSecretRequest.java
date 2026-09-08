package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload to create a new project secret.
 *
 * <p>Validates static schema constraints. Dynamic length limits configured in {@code SecretConfig}
 * are enforced at the resource level.
 *
 * @param description Optional description for the secret.
 * @param value       Plaintext secret value to seal and store.
 */
public record CreateSecretRequest(
        // Ensure both null and pattern mismatches produce consistent validation messages.
        @NotNull(message = CreateSecretRequest.NAME_REJECTED)
        @Pattern(regexp = CreateSecretRequest.NAME, message = CreateSecretRequest.NAME_REJECTED)
        String name,
        @Nullable String description,
        @NotEmpty(message = "value is required") String value
) {
    /** Pattern for valid secret names (environment variable style). */
    public static final String NAME = "[A-Za-z_][A-Za-z0-9_]{0,63}";

    static final String NAME_REJECTED = "name must match [A-Za-z_][A-Za-z0-9_]\\{0,63\\}";
}
