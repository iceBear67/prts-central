package io.ib67.prts.job.task;

import io.ib67.prts.agent.job.JobSpec;
import jakarta.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Values a {@link io.ib67.prts.job.task.entity.Task} contributes to every job launched under it.
 *
 * <p>Stored as {@code jsonb} on the task row, so it must stay Jackson-round-trippable.
 *
 * <p>Task contributions are authorized once, when the task is written, and are therefore never routed
 * through {@link io.ib67.prts.agent.job.JobSpecOverride} — that path gates every supplied field against
 * the <em>caller's</em> {@code job:spec:*} permissions, including empty collections.
 *
 * @param resourceClass Replaces the template's default class. Null means the template decides.
 */
public record TaskScope(
        Map<String, String> environment,
        Map<String, String> labels,
        @Nullable String resourceClass
) {
    /** Environment variable naming the task a job belongs to. Callers cannot override it. */
    public static final String TASK_ID_ENV = "PRTS_TASK_ID";
    /** Label naming the task a job belongs to. Callers cannot override it. */
    public static final String TASK_ID_LABEL = "prts.task";

    public static final TaskScope EMPTY = new TaskScope(Map.of(), Map.of(), null);

    public TaskScope {
        environment = Objects.requireNonNullElse(environment, Map.of());
        labels = Objects.requireNonNullElse(labels, Map.of());
        resourceClass = resourceClass == null || resourceClass.isBlank() ? null : resourceClass;
    }

    /**
     * Layers this scope's defaults over a template spec, beneath any caller override.
     *
     * <p>Precedence is template &lt; task &lt; override: the task is more specific than the template it
     * instantiates, and a job may still specialize what its task declares.
     */
    public JobSpec defaultsTo(JobSpec base) {
        Objects.requireNonNull(base, "base");
        if (environment.isEmpty() && labels.isEmpty()) {
            return base;
        }
        return new JobSpec(
                base.image(),
                base.description(),
                merged(base.environment(), environment),
                merged(base.labels(), labels),
                base.command(),
                base.volumes(),
                base.timeout(),
                base.lock(),
                base.secret());
    }

    /**
     * Applies what a caller may not override: the volumes the task mounts, and the task's identity.
     *
     * <p>Runs after {@code JobSpecOverride.applyTo}. Identity has to win, or a caller could claim
     * membership in a task by writing {@link #TASK_ID_ENV} into its own override.
     *
     * @param volumes the task's mounts, keyed by volume ID
     */
    public static JobSpec bindTo(JobSpec spec, UUID taskId, Map<UUID, JobSpec.VolumeSpec> volumes) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(volumes, "volumes");
        return new JobSpec(
                spec.image(),
                spec.description(),
                merged(spec.environment(), Map.of(TASK_ID_ENV, taskId.toString())),
                merged(spec.labels(), Map.of(TASK_ID_LABEL, taskId.toString())),
                spec.command(),
                merged(spec.volumes(), volumes),
                spec.timeout(),
                spec.lock(),
                spec.secret());
    }

    // Always builds a new map: JobSpec collections are not defensively copied, and a spec that skipped
    // every override still holds the managed JobSpecTemplate's own instance.
    private static <K, V> Map<K, V> merged(Map<K, V> base, Map<K, V> top) {
        if (top.isEmpty()) {
            return base;
        }
        var merged = new LinkedHashMap<>(base);
        merged.putAll(top);
        return merged;
    }
}
