package io.ib67.prts.dto.admin;

import io.ib67.prts.job.entity.Artifact;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored artifact together with the job and project it came from, which the per-job
 * {@code JobView.ArtifactView} has no reason to name and a cross-project listing cannot do without.
 *
 * @param size              Bytes the object occupies.
 * @param projectArchivedAt When the owning project was archived, or null if it is active. A
 *                          cross-project row is deleted through its own project, which refuses
 *                          while archived, so a caller gating the delete needs the state here.
 */
public record AdminArtifactView(
        UUID id,
        String name,
        UUID jobId,
        UUID projectId,
        String projectName,
        @Nullable Instant projectArchivedAt,
        Instant createdAt,
        long size
) {
    public AdminArtifactView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static AdminArtifactView of(Artifact artifact) {
        var job = artifact.getJob();
        var project = job.getProject();
        return new AdminArtifactView(
                artifact.getId(),
                artifact.getName(),
                job.getId(),
                project.getId(),
                project.getName(),
                project.getArchivedAt(),
                artifact.getCreatedAt(),
                artifact.getSizeBytes());
    }
}
