package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateSubAccountRequest(@NotBlank(message = "name is required") String name) {
    public CreateSubAccountRequest {
        name = name == null ? null : name.strip();
    }
}
