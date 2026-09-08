package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpec;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound shape of a {@link JobSpec}.
 *
 * <p>Mirrors the spec with the containers nullable, so a payload may leave out what it does not set;
 * {@link #toSpec()} normalizes them the way {@code JobSpec} does.
 */
public record JobSpecRequest(
        @NotBlank(message = "spec.image is required") String image,
        @Nullable Map<String, String> environment,
        @Nullable Map<String, String> labels,
        @Nullable List<String> command,
        @Nullable Map<UUID, JobSpec.VolumeSpec> volumes,
        @PositiveOrZero(message = "spec.timeout must be >= 0") long timeout,
        @Nullable String lock
) {
    public JobSpecRequest {
        image = image == null ? null : image.strip();
    }

    /** Secrets are never part of a template: they are resolved per dispatch. */
    public JobSpec toSpec() {
        return new JobSpec(image, environment, labels, command, volumes, timeout, lock, null);
    }
}
