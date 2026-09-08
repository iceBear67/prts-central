package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Request payload to open a project. */
public record CreateProjectRequest(@NotBlank(message = "name is required") String name) {
    public CreateProjectRequest {
        // Normalization only: the constraint above is what rejects, and it sees the stripped value.
        name = name == null ? null : name.strip();
    }
}
