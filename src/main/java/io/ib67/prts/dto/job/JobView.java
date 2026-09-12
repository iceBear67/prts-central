package io.ib67.prts.dto.job;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * View representing a job's execution state, metadata, and artifacts.
 *
 * @param resourceClass The resolved resource class name used for execution.
 * @param createRequest Request payload needed to re-run this job, or null if omitted or forbidden.
 * @param requestedBy   Who requested the job.
 */
public record JobView(
        UUID id,
        UUID projectId,
        Instant createdAt,
        @Nullable Instant completedAt,
        JobState state,
        @Nullable UUID worker,
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

    /** Builds the view for a lone job, resolving its requester and artifacts. */
    public static JobView of(Job job, @Nullable CreateJobRequest createRequest) {
        return of(job, UserInfo.of(job.getRequestedBy()), Artifact.listByJob(job.getId()), createRequest);
    }

    /**
     * Builds the views for a listing, resolving the whole page's requesters and artifacts in one
     * query each.
     *
     * @param createRequest each job's re-run payload, or null where it is omitted or forbidden
     */
    public static List<JobView> of(List<Job> jobs, Function<Job, CreateJobRequest> createRequest) {
        var users = User.mapByIds(jobs.stream().map(Job::getRequestedBy).distinct().toList());
        var artifacts = Artifact.listByJobs(jobs.stream().map(Job::getId).toList()).stream()
                .collect(Collectors.groupingBy(artifact -> artifact.getJob().getId()));
        return jobs.stream()
                .map(job -> of(
                        job,
                        UserInfo.of(job.getRequestedBy(), users.get(job.getRequestedBy())),
                        artifacts.getOrDefault(job.getId(), List.of()),
                        createRequest.apply(job)))
                .toList();
    }

    private static JobView of(
            Job job, UserInfo requestedBy, List<Artifact> artifacts, @Nullable CreateJobRequest createRequest) {
        return new JobView(
                job.getId(),
                job.getProject().getId(),
                job.getCreatedAt(),
                job.getCompletedAt(),
                job.getState(),
                job.getWorker(),
                requestedBy,
                job.getResourceClass().getName(),
                SpecView.of(job.getSpec()),
                artifacts.stream().map(ArtifactView::of).toList(),
                createRequest);
    }
}
