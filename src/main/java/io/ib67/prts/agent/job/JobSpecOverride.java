package io.ib67.prts.agent.job;

import io.ib67.prts.agent.job.JobSpec.VolumeSpec;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Overrides fields of a template {@link JobSpec}.
 *
 * <p>Specified fields override base template values. Scalar fields replace existing values,
 * maps merge by key, and lists append to base values. Null fields leave the template value unchanged.
 */
public record JobSpecOverride(
        @Nullable @Size(max = JobSpec.MAX_IMAGE_LENGTH,
                message = "override.image must be at most {max} characters") String image,
        @Nullable @Size(max = 256, message = "override.description must be at most {max} characters")
        String description,
        @Nullable @Size(max = JobSpec.MAX_ENTRIES,
                message = "override.environment must have at most {max} entries")
        Map<@Size(max = JobSpec.MAX_ENTRY_KEY_LENGTH, message = "a name must be at most {max} characters") String,
                @Size(max = JobSpec.MAX_ENTRY_VALUE_LENGTH, message = "a value must be at most {max} characters") String>
                environment,
        @Nullable @Size(max = JobSpec.MAX_ENTRIES, message = "override.labels must have at most {max} entries")
        Map<@Size(max = JobSpec.MAX_ENTRY_KEY_LENGTH, message = "a name must be at most {max} characters") String,
                @Size(max = JobSpec.MAX_ENTRY_VALUE_LENGTH, message = "a value must be at most {max} characters") String>
                labels,
        @Nullable @Size(max = JobSpec.MAX_COMMAND_ARGUMENTS,
                message = "override.command must have at most {max} arguments")
        List<@Size(max = JobSpec.MAX_ARGUMENT_LENGTH, message = "an argument must be at most {max} characters") String> command,
        @Nullable @Size(max = JobSpec.MAX_VOLUMES, message = "override.volumes must have at most {max} entries")
        Map<UUID, @Valid VolumeSpec> volumes,
        @Nullable @PositiveOrZero(message = "override.timeout must be >= 0")
        @Max(value = JobSpec.MAX_TIMEOUT_SECONDS,
                message = "override.timeout must be at most {value} seconds") Long timeout,
        @Nullable @Size(max = JobSpec.MAX_LOCK_LENGTH,
                message = "override.lock must be at most {max} characters") String lock
) {
    public JobSpec applyTo(JobSpec base, JobSpecOverrideAuthorizer authorizer) {
        Objects.requireNonNull(base, "spec");
        Objects.requireNonNull(authorizer, "authorizer");
        return new JobSpec(
                apply(image, authorizer::image, base.image()),
                apply(description, authorizer::description, base.description()),
                mergeMap(environment, authorizer::environment, base.environment()),
                mergeMap(labels, authorizer::labels, base.labels()),
                mergeList(command, authorizer::command, base.command()),
                mergeMap(volumes, authorizer::volumes, base.volumes()),
                timeout != null ? authorizer.timeout(timeout) : base.timeout(),
                apply(lock, authorizer::lock, base.lock()), base.secret());
    }

    private static <T> T apply(T override, Function<T, T> gated, T fallback) {
        return override != null ? gated.apply(override) : fallback;
    }

    // Gating occurs before merging so authorizers inspect only user-supplied inputs.
    private static <K, V> Map<K, V> mergeMap(
            @Nullable Map<K, V> override, Function<Map<K, V>, Map<K, V>> gated, Map<K, V> base) {
        if (override == null) {
            return base;
        }
        var merged = new LinkedHashMap<>(base);
        merged.putAll(gated.apply(override));
        return merged;
    }

    private static <E> List<E> mergeList(
            @Nullable List<E> override, Function<List<E>, List<E>> gated, List<E> base) {
        if (override == null) {
            return base;
        }
        var merged = new ArrayList<>(base);
        merged.addAll(gated.apply(override));
        return merged;
    }
}
