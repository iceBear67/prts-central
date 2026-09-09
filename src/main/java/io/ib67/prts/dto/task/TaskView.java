package io.ib67.prts.dto.task;

import io.ib67.prts.job.task.TaskScope;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View of a task scope.
 *
 * @param closedAt null until teardown finished
 */
public record TaskView(
        UUID id,
        UUID projectId,
        String name,
        TaskState state,
        TaskScope scope,
        UUID createdBy,
        Instant createdAt,
        @Nullable Instant closedAt
) {
    public TaskView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static TaskView of(Task task) {
        return new TaskView(
                task.getId(),
                task.getProject().getId(),
                task.getName(),
                task.getState(),
                task.getScope(),
                task.getCreatedBy(),
                task.getCreatedAt(),
                task.getClosedAt());
    }
}
