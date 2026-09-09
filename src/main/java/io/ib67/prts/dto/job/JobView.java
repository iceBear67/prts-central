package io.ib67.prts.dto.job;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a job's execution state, metadata, and artifacts.
 *
 * @param resourceClass The resolved resource class name used for execution.
 * @param createRequest Request payload needed to re-run this job, or null if omitted or forbidden.
 * @param requestedBy   User ID who requested the job.
 */
public record JobView(
        UUID id,
        UUID projectId,
        Instant createdAt,
        @Nullable Instant completedAt,
        JobState state,
        @Nullable UUID worker,
        UUID requestedBy,
        String resourceClass,
        @Nullable SpecView spec,
        List<ArtifactView> artifacts,
        @Nullable CreateJobRequest createRequest
) implements JobStatusView {
    public static final String TYPE = "job";

    public JobView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(resourceClass, "resourceClass");
        Objects.requireNonNull(artifacts, "artifacts");
    }

    public record ArtifactView(UUID id, String name) {
        public ArtifactView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }

        public static ArtifactView of(Artifact artifact) {
            return new ArtifactView(artifact.getId(), artifact.getName());
        }
    }

    /** Public view of a {@link JobSpec}, excluding sensitive values such as secrets. */
    public record SpecView(
            String image,
            Map<String, String> environment,
            Map<String, String> labels,
            List<String> command,
            Map<UUID, JobSpec.VolumeSpec> volumes,
            long timeout,
            String lock
    ) {
        public SpecView {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(labels, "labels");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(volumes, "volumes");
            Objects.requireNonNull(lock, "lock");
        }

        @Nullable
        public static SpecView of(@Nullable JobSpec spec) {
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

    public static JobView of(Job job, List<Artifact> artifacts) {
        return of(job, artifacts, null);
    }

    public static JobView of(Job job, List<Artifact> artifacts, @Nullable CreateJobRequest createRequest) {
        return new JobView(
                job.getId(),
                job.getProject().getId(),
                job.getCreatedAt(),
                job.getCompletedAt(),
                job.getState(),
                job.getWorker(),
                job.getRequestedBy(),
                job.getResourceClass().getName(),
                SpecView.of(job.getSpec()),
                artifacts.stream().map(ArtifactView::of).toList(),
                createRequest);
    }
}
