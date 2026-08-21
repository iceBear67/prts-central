package io.ib67.prts.dto;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.project.Artifact;
import io.ib67.prts.project.Job;
import io.ib67.prts.project.JobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JobView(
        UUID id,
        UUID projectId,
        Instant createdAt,
        @Nullable Instant completedAt,
        JobState state,
        @Nullable UUID worker,
        @Nullable SpecView spec,
        List<ArtifactView> artifacts
) {
    public record ArtifactView(UUID id, String name) {
        public static ArtifactView of(Artifact artifact) {
            return new ArtifactView(artifact.getId(), artifact.getName());
        }
    }

    /**
     * The one place that decides what of a spec a client may see; {@link JobSpecTemplateView} goes
     * through it too, so a new {@link JobSpec} field is not published by default.
     */
    public record SpecView(
            String image,
            Map<String, String> environment,
            Map<String, String> labels,
            List<String> command,
            Map<UUID, JobSpec.VolumeSpec> volumes,
            long timeout,
            @Nullable String lock
    ) {
        public static SpecView of(JobSpec spec) {
            if (spec == null) {
                return null;
            }
            return new SpecView(
                    spec.image(),
                    spec.environment(),
                    spec.labels(),
                    spec.command(),
                    spec.volumes(),
                    spec.timeout(),
                    spec.lock());
        }
    }

    /** A job that has produced nothing yet — one just created, or just re-run. */
    public static JobView of(Job job) {
        return of(job, List.of());
    }

    /**
     * Reads only what a detached job carries — the project for its id alone, which a lazy proxy
     * answers without loading. Keep it that way: callers map jobs after the transaction closed.
     */
    public static JobView of(Job job, List<Artifact> artifacts) {
        return new JobView(
                job.getId(),
                job.getProject().getId(),
                job.getCreatedAt(),
                job.getCompletedAt(),
                job.getState(),
                job.getWorker(),
                SpecView.of(job.getSpec()),
                artifacts.stream().map(ArtifactView::of).toList());
    }
}
