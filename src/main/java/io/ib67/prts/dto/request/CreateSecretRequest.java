package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload to create a new project secret.
 *
 * <p>Carries what the shape decides. The configured length limits on the value and the description are
 * the resource's to enforce, since {@code SecretConfig} is not a compile-time constant and so cannot
 * become a {@code @Size}.
 *
 * @param description Optional description for the secret.
 * @param value       Plaintext secret value to seal and store.
 */
public record CreateSecretRequest(
        // @Pattern passes a null through, so @NotNull carries the same message to keep either failure
        // reading the same way. Braces are message-template syntax, hence the escapes.
        @NotNull(message = CreateSecretRequest.NAME_REJECTED)
        @Pattern(regexp = CreateSecretRequest.NAME, message = CreateSecretRequest.NAME_REJECTED)
        String name,
        @Nullable String description,
        @NotEmpty(message = "value is required") String value
) {
    /** Valid environment variable style secret name pattern. */
    public static final String NAME = "[A-Za-z_][A-Za-z0-9_]{0,63}";

    static final String NAME_REJECTED = "name must match [A-Za-z_][A-Za-z0-9_]\\{0,63\\}";
}
