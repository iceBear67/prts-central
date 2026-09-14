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
 * Execution attributes contributed by a {@link io.ib67.prts.job.task.entity.Task} to jobs executed under it.
 *
 * <p>Stored as {@code jsonb} on the task record. Permissions are validated upon task creation/update
 * via {@link #authorize(JobSpecOverrideAuthorizer)}.
 *
 * @param resourceClass Default resource class for task jobs, or null to inherit from template.
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
    /** Environment variable injected into jobs indicating task association. */
    public static final String TASK_ID_ENV = "PRTS_TASK_ID";
    /** Label injected into jobs indicating task association. */
    public static final String TASK_ID_LABEL = "prts.task";

    public static final TaskScope EMPTY = new TaskScope(Map.of(), Map.of(), null);

    public TaskScope {
        environment = Objects.requireNonNullElse(environment, Map.of());
        labels = Objects.requireNonNullElse(labels, Map.of());
        resourceClass = resourceClass == null || resourceClass.isBlank() ? null : resourceClass;
    }

    /**
     * Validates that the caller has permissions to configure the non-empty attributes of this scope.
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
     * Overlays task default environment and labels on top of the template specification, beneath caller overrides.
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
     * Binds immutable task properties (task ID and attached volumes) onto the job specification.
     *
     * @param volumes Attached volume mounts keyed by volume ID.
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
