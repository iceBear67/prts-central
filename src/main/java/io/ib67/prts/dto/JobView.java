package io.ib67.prts.dto;

import io.ib67.prts.project.Artifact;
import io.ib67.prts.project.Job;
import io.ib67.prts.project.JobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JobView(
        UUID id,
        UUID projectId,
        Instant createdAt,
        @Nullable Instant completedAt,
        JobState state,
        @Nullable UUID worker,
        List<ArtifactView> artifacts
) {
    public record ArtifactView(UUID id, String name) {
        public static ArtifactView of(Artifact artifact) {
            return new ArtifactView(artifact.getId(), artifact.getName());
        }
    }

    public static JobView of(Job job, List<Artifact> artifacts) {
        return new JobView(
                job.getId(),
                job.getProject().getId(),
                job.getCreatedAt(),
                job.getCompletedAt(),
                job.getState(),
                job.getWorker(),
                artifacts.stream().map(ArtifactView::of).toList());
    }
}
