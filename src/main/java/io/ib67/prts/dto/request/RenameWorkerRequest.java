package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Request payload to rename a registered worker. */
public record RenameWorkerRequest(@NotBlank(message = "name is required") String name) {
    public RenameWorkerRequest {
        name = name == null ? null : name.strip();
    }
}
