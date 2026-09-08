package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to update a project secret.
 *
 * <p>The configured length limits are the resource's to enforce, since {@code SecretConfig} is not a
 * compile-time constant and so cannot become a {@code @Size}.
 *
 * @param description New description, or empty to clear. Null keeps the existing description.
 * @param value       New plaintext secret value. Null keeps the existing secret value.
 */
public record UpdateSecretRequest(
        @Nullable String description,
        // Null keeps the stored value, so only an explicitly empty one is refused: @Size passes null.
        @Nullable @Size(min = 1, message = "value is required") String value
) {
    public UpdateSecretRequest {
        // Not a constraint: "at least one of these two" spans components, which no standard constraint
        // expresses. A class-level validator would be two more types for one condition.
        if (description == null && value == null) {
            throw new BadRequestException(
                    "description or value is required; a blank description clears it");
        }
    }
}
