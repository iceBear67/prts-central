package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpec.VolumeSpec;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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
 *
 * <p>The caps come from {@link JobSpec}: everything here is persisted as {@code jsonb} and resent on
 * every dispatch, so each collection carries a bound rather than leaving the request body limit to be
 * the only one.
 */
public record JobSpecRequest(
        @NotBlank(message = "spec.image is required")
        @Size(max = JobSpec.MAX_IMAGE_LENGTH, message = "spec.image must be at most {max} characters")
        String image,
        @Nullable @Size(max = 256, message = "spec.description must be at most {max} characters")
        String description,
        @Nullable @Size(max = JobSpec.MAX_ENTRIES, message = "spec.environment must have at most {max} entries")
        Map<@Size(max = JobSpec.MAX_ENTRY_KEY_LENGTH, message = "a name must be at most {max} characters") String,
                @Size(max = JobSpec.MAX_ENTRY_VALUE_LENGTH, message = "a value must be at most {max} characters") String> environment,
        @Nullable @Size(max = JobSpec.MAX_ENTRIES, message = "spec.labels must have at most {max} entries")
        Map<@Size(max = JobSpec.MAX_ENTRY_KEY_LENGTH, message = "a name must be at most {max} characters") String,
                @Size(max = JobSpec.MAX_ENTRY_VALUE_LENGTH, message = "a value must be at most {max} characters") String> labels,
        @Nullable @Size(max = JobSpec.MAX_COMMAND_ARGUMENTS, message = "spec.command must have at most {max} arguments")
        List<@Size(max = JobSpec.MAX_ARGUMENT_LENGTH, message = "an argument must be at most {max} characters") String> command,
        @Nullable @Size(max = JobSpec.MAX_VOLUMES, message = "spec.volumes must have at most {max} entries")
        Map<UUID, @Valid VolumeSpec> volumes,
        @PositiveOrZero(message = "spec.timeout must be >= 0")
        @Max(value = JobSpec.MAX_TIMEOUT_SECONDS, message = "spec.timeout must be at most {value} seconds")
        long timeout,
        @Nullable @Size(max = JobSpec.MAX_LOCK_LENGTH, message = "spec.lock must be at most {max} characters")
        String lock
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
