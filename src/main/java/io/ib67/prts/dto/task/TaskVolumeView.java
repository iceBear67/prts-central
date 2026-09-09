package io.ib67.prts.dto.task;

import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.job.task.entity.TaskVolume;

import java.util.Objects;
import java.util.UUID;

/**
 * View of a volume as a task mounts it.
 *
 * @param mountPoint where this task's jobs mount it; another task may mount it elsewhere
 * @param length     allocated bytes
 */
public record TaskVolumeView(
        UUID volumeId,
        String name,
        String mountPoint,
        UUID workerId,
        long length,
        VolumeState state
) {
    public TaskVolumeView {
        Objects.requireNonNull(volumeId, "volumeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mountPoint, "mountPoint");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(state, "state");
    }

    public static TaskVolumeView of(TaskVolume mount) {
        var volume = mount.getVolume();
        return new TaskVolumeView(
                volume.getId(),
                volume.getName(),
                mount.getMountPoint(),
                volume.getWorker().getId(),
                volume.getLength(),
                volume.getState());
    }
}
