package io.ib67.prts.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload to create a project.
 *
 * @param description What the project is for. Null or blank leaves it empty.
 */
public record CreateProjectRequest(
        @NotBlank(message = "name is required") String name,
        @Nullable @Size(max = 256, message = "description must be at most 256 characters")
        String description
) {
    public CreateProjectRequest {
        name = name == null ? null : name.strip();
        description = description == null ? "" : description.strip();
    }
}
