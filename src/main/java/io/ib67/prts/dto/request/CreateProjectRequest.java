package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Request payload to create a project. */
public record CreateProjectRequest(@NotBlank(message = "name is required") String name) {
    public CreateProjectRequest {
        name = name == null ? null : name.strip();
    }
}
