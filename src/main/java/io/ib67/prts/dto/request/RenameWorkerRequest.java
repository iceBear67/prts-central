package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request payload to rename a registered worker. */
public record RenameWorkerRequest(
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name
) {
    public RenameWorkerRequest {
        name = name == null ? null : name.strip();
    }
}
