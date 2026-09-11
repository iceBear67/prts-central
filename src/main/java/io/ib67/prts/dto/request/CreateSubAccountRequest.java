package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSubAccountRequest(
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name
) {
    public CreateSubAccountRequest {
        name = name == null ? null : name.strip();
    }
}
