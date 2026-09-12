package io.ib67.prts.dto.job;

import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * View representing a queued job pending dispatch.
 *
 * @param resourceClass The resource class the entry was pinned to when it was accepted.
 * @param request       The create request payload, or null if hidden by caller permissions.
 * @param nextAttemptAt Timestamp when the next dispatch attempt is scheduled.
 * @param jobId         The resulting Job ID once dispatched.
 */
public record PendingJobView(
        UUID id,
        UUID projectId,
        PendingJobState state,
        UserInfo requestedBy,
        String resourceClass,
        Instant createdAt,
        Instant expiresAt,
        @Nullable Instant nextAttemptAt,
        int attempts,
        @Nullable String lastError,
        @Nullable UUID jobId,
        @Nullable CreateJobRequest request
) implements JobStatusView {
    public static final String TYPE = "pending";

    public PendingJobView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(resourceClass, "resourceClass");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Builds the view for a lone queued job, resolving its requester. */
    public static PendingJobView of(PendingJob pending, @Nullable CreateJobRequest request) {
        return of(pending, UserInfo.of(pending.getRequestedBy()), request);
    }

    /**
     * Builds the views for a listing, resolving the whole page's requesters in one query.
     *
     * @param request each entry's create payload, or null where it is hidden by caller permissions
     */
    public static List<PendingJobView> of(
            List<PendingJob> queued, Function<PendingJob, CreateJobRequest> request) {
        var users = User.mapByIds(queued.stream().map(PendingJob::getRequestedBy).distinct().toList());
        return queued.stream()
                .map(pending -> of(
                        pending,
                        UserInfo.of(pending.getRequestedBy(), users.get(pending.getRequestedBy())),
                        request.apply(pending)))
                .toList();
    }

    private static PendingJobView of(
            PendingJob pending, UserInfo requestedBy, @Nullable CreateJobRequest request) {
        return new PendingJobView(
                pending.getId(),
                pending.getProject().getId(),
                pending.getState(),
                requestedBy,
                pending.getRequest().resourceClass(),
                pending.getCreatedAt(),
                pending.getExpiresAt(),
                pending.getState().isSettled() ? null : pending.getNextAttemptAt(),
                pending.getAttempts(),
                pending.getLastError(),
                pending.getJobId(),
                request);
    }
}
