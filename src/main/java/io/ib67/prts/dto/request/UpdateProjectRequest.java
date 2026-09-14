package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to update a project.
 *
 * @param name        new project name, or null to keep unchanged
 * @param description new project description (blank to clear), or null to keep unchanged
 */
public record UpdateProjectRequest(
        @Nullable @Size(min = 1, max = 200,
                message = "name must not be blank and at most 200 characters") String name,
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
