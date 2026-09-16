package io.ib67.prts.dto.job;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.WorkerInfo;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.JobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a job's execution state, metadata, and artifacts. Built by {@code JobService.viewOf}.
 *
 * @param createdAt     When the job was enqueued, which is not when it began running.
 * @param startedAt     When a worker took the job; null while it is still queued, and on one
 *                      cancelled before placement. {@code startedAt - createdAt} is queue time.
 * @param resourceClass The resolved resource class name used for execution.
 * @param createRequest Request payload needed to re-run this job, or null if omitted or forbidden.
 * @param requestedBy   Who requested the job.
 * @param worker        The host that ran it, or null while unplaced.
 */
public record JobView(
        UUID id,
        UUID projectId,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt,
        JobState state,
        @Nullable WorkerInfo worker,
        UserInfo requestedBy,
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

    @Override
    public String type() {
        return TYPE;
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
            String description,
            Map<String, String> environment,
            Map<String, String> labels,
            List<String> command,
            Map<UUID, JobSpec.VolumeSpec> volumes,
            long timeout,
            String lock
    ) {
        public SpecView {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(description, "description");
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
                    spec.description(),
                    spec.environment(),
                    spec.labels(),
                    spec.command(),
                    spec.volumes(),
                    spec.timeout(),
                    spec.lock());
        }
    }

}
