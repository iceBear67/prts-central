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
 * @param trackedAt link to the issue, pull request or ticket the task follows; empty if it tracks none
 * @param closedAt  null until teardown finished
 */
public record TaskView(
        UUID id,
        UUID projectId,
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
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(trackedAt, "trackedAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Builds the view for a lone task, resolving its opener. */
    public static TaskView of(Task task) {
        return of(task, UserInfo.of(task.getCreatedBy()));
    }

    /** Builds the views for a listing, resolving the whole page's openers in one query. */
    public static List<TaskView> of(List<Task> tasks) {
        var users = User.mapByIds(tasks.stream().map(Task::getCreatedBy).distinct().toList());
        return tasks.stream()
                .map(task -> of(task, UserInfo.of(task.getCreatedBy(), users.get(task.getCreatedBy()))))
                .toList();
    }

    private static TaskView of(Task task, UserInfo createdBy) {
        return new TaskView(
                task.getId(),
                task.getProject().getId(),
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
