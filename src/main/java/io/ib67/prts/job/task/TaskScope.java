package io.ib67.prts.job.task;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverrideAuthorizer;
import io.ib67.prts.dto.request.CreateResourceClassRequest;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Values a {@link io.ib67.prts.job.task.entity.Task} contributes to every job launched under it.
 *
 * <p>Stored as {@code jsonb} on the task row, so it must stay Jackson-round-trippable.
 *
 * <p>Task contributions are authorized once, when the task is written ({@link #authorize}), and are
 * therefore never routed through {@link io.ib67.prts.agent.job.JobSpecOverride} — that path gates every
 * supplied field against the <em>caller's</em> {@code job:spec:*} permissions, including empty
 * collections.
 *
 * @param resourceClass Replaces the template's default class. Null means the template decides.
 */
public record TaskScope(
        @Size(max = JobSpec.MAX_ENTRIES, message = "scope.environment must have at most {max} entries")
        Map<@Size(max = JobSpec.MAX_ENTRY_KEY_LENGTH, message = "a name must be at most {max} characters") String,
                @Size(max = JobSpec.MAX_ENTRY_VALUE_LENGTH, message = "a value must be at most {max} characters") String>
                environment,
        @Size(max = JobSpec.MAX_ENTRIES, message = "scope.labels must have at most {max} entries")
        Map<@Size(max = JobSpec.MAX_ENTRY_KEY_LENGTH, message = "a name must be at most {max} characters") String,
                @Size(max = JobSpec.MAX_ENTRY_VALUE_LENGTH, message = "a value must be at most {max} characters") String>
                labels,
        @Nullable @Pattern(regexp = CreateResourceClassRequest.NAME,
                message = "scope.resourceClass is not a valid resource class name")
        String resourceClass
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
     * Charges the writer for what this scope contributes, field by field.
     *
     * <p>Every job under the task adopts these values without passing them through
     * {@link io.ib67.prts.agent.job.JobSpecOverride}, so this is the one place they are paid for. It has
     * to run wherever a scope is written, or {@code task:manage} — a {@code MEMBER} by default — would
     * be a way around {@code job:spec:environment}, {@code job:spec:labels} and {@code job:resource-class}.
     *
     * <p>Contributing nothing costs nothing: an absent field and an empty one are the same value here
     * (unlike an override, where supplied-but-empty is still gated), so only what the scope carries is
     * checked.
     */
    public void authorize(JobSpecOverrideAuthorizer authorizer) {
        Objects.requireNonNull(authorizer, "authorizer");
        if (!environment.isEmpty()) {
            authorizer.environment(environment);
        }
        if (!labels.isEmpty()) {
            authorizer.labels(labels);
        }
        if (resourceClass != null) {
            authorizer.resourceClass(resourceClass);
        }
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
