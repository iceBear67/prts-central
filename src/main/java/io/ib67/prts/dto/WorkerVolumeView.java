package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.agent.worker.entity.WorkerVolume;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View of a storage volume hosted on a worker.
 *
 * @param length Allocated bytes.
 * @param used   Bytes currently in use.
 */
public record WorkerVolumeView(
        UUID id,
        String name,
        UUID projectId,
        String projectName,
        UUID workerId,
        Instant createdAt,
        long length,
        long used,
        VolumeState state
) {
    public WorkerVolumeView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(state, "state");
    }

    public static WorkerVolumeView of(WorkerVolume volume) {
        var project = volume.getProject();
        return new WorkerVolumeView(
                volume.getId(),
                volume.getName(),
                project.getId(),
                project.getName(),
                volume.getWorker().getId(),
                volume.getCreatedAt(),
                volume.getLength(),
                volume.getUsed(),
                volume.getState());
    }
}
