package io.ib67.prts.dto.task;

import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.job.task.TaskScope;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskState;
import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * View of a task scope.
 *
 * @param trackedAt Issue or pull request link; empty string if none.
 * @param closedAt  Timestamp when task was closed, or null if open.
 */
public record TaskView(
        UUID id,
        UUID projectId,
        String projectName,
        String name,
        String description,
        String trackedAt,
        TaskState state,
        TaskScope scope,
        UserInfo createdBy,
        Instant createdAt,
        @Nullable Instant closedAt
) {
    public TaskView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(trackedAt, "trackedAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Creates a TaskView for a single task, resolving creator details. */
    public static TaskView of(Task task) {
        return of(task, UserInfo.of(task.getCreatedBy()));
    }

    /** Creates TaskViews for a list of tasks, resolving creator details in bulk. */
    public static List<TaskView> of(List<Task> tasks) {
        var users = User.mapByIds(tasks.stream().map(Task::getCreatedBy).distinct().toList());
        return tasks.stream()
                .map(task -> of(task, UserInfo.of(task.getCreatedBy(), users.get(task.getCreatedBy()))))
                .toList();
    }

    private static TaskView of(Task task, UserInfo createdBy) {
        var project = task.getProject();
        return new TaskView(
                task.getId(),
                project.getId(),
                project.getName(),
                task.getName(),
                task.getDescription(),
                task.getTrackedAt(),
                task.getState(),
                task.getScope(),
                createdBy,
                task.getCreatedAt(),
                task.getClosedAt());
    }
}
