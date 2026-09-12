package io.ib67.prts.dto.task;

import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskVolume;

import java.util.List;
import java.util.Objects;

/**
 * A task together with what it currently mounts.
 *
 * @param volumes the task's mounts; the volumes themselves belong to the project and outlive the task
 */
public record TaskDetailView(
        TaskView task,
        List<TaskVolumeView> volumes
) {
    public TaskDetailView {
        Objects.requireNonNull(task, "task");
        volumes = Objects.requireNonNullElse(volumes, List.of());
    }

    public static TaskDetailView of(Task task, List<TaskVolume> mounts) {
        return new TaskDetailView(
                TaskView.of(task),
                mounts.stream().map(TaskVolumeView::of).toList());
    }
}
