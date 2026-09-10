package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to update a project.
 *
 * @param name        New name. Null keeps the existing name.
 * @param description New description, or blank to clear. Null keeps the existing description.
 */
public record UpdateProjectRequest(
        @Nullable @Size(min = 1, message = "name must not be blank") String name,
        @Nullable @Size(max = 256, message = "description must be at most 256 characters")
        String description
) {
    public UpdateProjectRequest {
        name = name == null ? null : name.strip();
        description = description == null ? null : description.strip();
        if (name == null && description == null) {
            throw new BadRequestException(
                    "name or description is required; a blank description clears it");
        }
    }
}
