package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.task.entity.TaskVolume;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A volume together with the tasks mounting it.
 *
 * <p>{@code task_volume} is many-to-many, and a delete refuses while any mount remains — which is
 * the question an operator has at the moment they are looking at a volume they want to remove.
 */
public record WorkerVolumeDetailView(
        WorkerVolumeView volume,
        List<Mount> mounts
) {
    public WorkerVolumeDetailView {
        Objects.requireNonNull(volume, "volume");
        mounts = Objects.requireNonNullElse(mounts, List.of());
    }

    /** @param mountPoint Path the task's jobs mount this volume at. */
    public record Mount(UUID taskId, String taskName, String mountPoint) {
        public Mount {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(taskName, "taskName");
            Objects.requireNonNull(mountPoint, "mountPoint");
        }

        public static Mount of(TaskVolume mount) {
            var task = mount.getTask();
            return new Mount(task.getId(), task.getName(), mount.getMountPoint());
        }
    }

    public static WorkerVolumeDetailView of(WorkerVolume volume, List<TaskVolume> mounts) {
        return new WorkerVolumeDetailView(
                WorkerVolumeView.of(volume), mounts.stream().map(Mount::of).toList());
    }
}
