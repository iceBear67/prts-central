package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request payload to allocate a worker volume for a project.
 *
 * @param sizeBytes Size in bytes to allocate on the selected worker.
 */
public record CreateVolumeRequest(
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,
        @Positive(message = "sizeBytes must be > 0") long sizeBytes
) {
    public CreateVolumeRequest {
        name = name == null ? null : name.strip();
    }
}
