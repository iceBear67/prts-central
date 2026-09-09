package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload to mount a project volume into a task.
 *
 * @param mountPoint absolute path the task's jobs mount the volume at
 */
public record AttachVolumeRequest(
        @NotBlank(message = "mountPoint is required")
        @Pattern(regexp = "/.*", message = "mountPoint must be an absolute path")
        String mountPoint
) {
    public AttachVolumeRequest {
        mountPoint = mountPoint == null ? null : mountPoint.strip();
    }
}
