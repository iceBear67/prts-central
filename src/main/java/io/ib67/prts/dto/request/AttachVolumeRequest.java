package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload to mount a project volume into a task.
 *
 * @param mountPoint absolute path the task's jobs mount the volume at
 */
public record AttachVolumeRequest(
        @NotBlank(message = "mountPoint is required")
        @Size(max = JobSpec.VolumeSpec.MAX_MOUNT_POINT_LENGTH,
                message = "mountPoint must be at most {max} characters")
        @Pattern(regexp = JobSpec.VolumeSpec.MOUNT_POINT,
                message = JobSpec.VolumeSpec.MOUNT_POINT_REJECTED)
        String mountPoint
) {
    public AttachVolumeRequest {
        mountPoint = mountPoint == null ? null : mountPoint.strip();
    }
}
