package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to update a project secret.
 *
 * @param description New description, or empty to clear. Null keeps the existing description.
 * @param value       New plaintext secret value. Null keeps the existing secret value.
 */
public record UpdateSecretRequest(
        @Nullable String description,
        @Nullable @Size(min = 1, message = "value is required") String value
) {
    public UpdateSecretRequest {
        if (description == null && value == null) {
            throw new BadRequestException(
                    "description or value is required; a blank description clears it");
        }
    }
}
