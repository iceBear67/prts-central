package io.ib67.prts.agent.job;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Present fields replace the template {@link JobSpec}. {@code null} means keep the template value;
 * an empty collection is an explicit override.
 */
public record JobSpecOverride(
        @Nullable String image,
        @Nullable Map<String, String> environment,
        @Nullable Map<String, String> secrets,
        @Nullable Map<String, String> labels,
        @Nullable List<String> command,
        @Nullable Map<UUID, JobSpec.VolumeSpec> volumes,
        @Nullable Long timeout
) {
    public JobSpec applyTo(JobSpec base, JobSpecOverridePermissions permissions) {
        Objects.requireNonNull(base, "spec");
        Objects.requireNonNull(permissions, "permissions");
        return new JobSpec(
                apply(image, permissions::image, base.image()),
                apply(environment, permissions::environment, base.environment()),
                apply(secrets, permissions::secrets, base.secrets()),
                apply(labels, permissions::labels, base.labels()),
                apply(command, permissions::command, base.command()),
                apply(volumes, permissions::volumes, base.volumes()),
                timeout != null ? permissions.timeout(timeout) : base.timeout());
    }

    private static <T> T apply(T override, Function<T, T> gated, T fallback) {
        return override != null ? gated.apply(override) : fallback;
    }
}
