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
 * Present fields override the template {@link JobSpec}; {@code null} means keep the template value.
 * Scalars replace, containers merge: map entries win per key, list entries are appended after the
 * template's. An empty container therefore changes nothing — and that is why absent is {@code null}
 * here rather than empty, unlike in {@link JobSpec}: only a field the caller actually supplied is
 * gated, so a no-op override must not cost a permission.
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
    public JobSpec applyTo(JobSpec base, JobSpecOverridePermissions permissions) {
        Objects.requireNonNull(base, "spec");
        Objects.requireNonNull(permissions, "permissions");
        return new JobSpec(
                apply(image, permissions::image, base.image()),
                mergeMap(environment, permissions::environment, base.environment()),
                mergeMap(labels, permissions::labels, base.labels()),
                mergeList(command, permissions::command, base.command()),
                mergeMap(volumes, permissions::volumes, base.volumes()),
                timeout != null ? permissions.timeout(timeout) : base.timeout(),
                apply(lock, permissions::lock, base.lock()), base.secret());
        // Not overridable: secrets are the project's, injected at dispatch, never requested.
    }

    private static <T> T apply(T override, Function<T, T> gated, T fallback) {
        return override != null ? gated.apply(override) : fallback;
    }

    // The gated call happens before merging so the permission interceptor still sees exactly what
    // the caller supplied. Insertion-ordered copies keep the jsonb representation stable.
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
