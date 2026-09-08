package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RenameProjectRequest(@NotBlank(message = "name is required") String name) {
    public RenameProjectRequest {
        name = name == null ? null : name.strip();
    }
}
