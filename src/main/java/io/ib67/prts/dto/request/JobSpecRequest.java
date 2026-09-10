package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpec;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request payload representation of a {@link JobSpec}.
 *
 * <p>Allows optional collection fields which are normalized to non-null values by {@link #toSpec()}.
 */
public record JobSpecRequest(
        @NotBlank(message = "spec.image is required") String image,
        @Nullable @Size(max = 256, message = "spec.description must be at most 256 characters")
        String description,
        @Nullable Map<String, String> environment,
        @Nullable Map<String, String> labels,
        @Nullable List<String> command,
        @Nullable Map<UUID, JobSpec.VolumeSpec> volumes,
        @PositiveOrZero(message = "spec.timeout must be >= 0") long timeout,
        @Nullable String lock
) {
    public JobSpecRequest {
        image = image == null ? null : image.strip();
        description = description == null ? null : description.strip();
    }

    /** Converts this request to a {@link JobSpec}, omitting secrets which are resolved at dispatch. */
    public JobSpec toSpec() {
        return new JobSpec(image, description, environment, labels, command, volumes, timeout, lock, null);
    }
}
