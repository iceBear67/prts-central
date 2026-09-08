package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.entity.WorkerVolume;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View of a storage volume hosted on a worker.
 *
 * @param length Allocated bytes.
 * @param used   Bytes in use, never above {@code length}.
 */
public record WorkerVolumeView(
        UUID id,
        String name,
        UUID projectId,
        String projectName,
        Instant createdAt,
        long length,
        long used
) {
    public WorkerVolumeView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static WorkerVolumeView of(WorkerVolume volume) {
        var project = volume.getProject();
        return new WorkerVolumeView(
                volume.getId(),
                volume.getName(),
                project.getId(),
                project.getName(),
                volume.getCreatedAt(),
                volume.getLength(),
                volume.getUsed());
    }
}
