package io.ib67.prts.agent.job;

import jakarta.annotation.Nullable;

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
        @Nullable String image,
        @Nullable Map<String, String> environment,
        @Nullable Map<String, String> labels,
        @Nullable List<String> command,
        @Nullable Map<UUID, JobSpec.VolumeSpec> volumes,
        @Nullable Long timeout,
        @Nullable String lock
) {
    public JobSpec applyTo(JobSpec base, JobSpecOverrideAuthorizer authorizer) {
        Objects.requireNonNull(base, "spec");
        Objects.requireNonNull(authorizer, "authorizer");
        return new JobSpec(
                apply(image, authorizer::image, base.image()),
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
