package io.ib67.prts.dto.job;

import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    /**
     * Builds the view with the requester resolved against a page of users.
     *
     * @param users the users a listing resolved, keyed by ID; a requester missing from it is
     *              rendered with a null name
     */
    public static PendingJobView of(PendingJob pending, Map<UUID, User> users, @Nullable CreateJobRequest request) {
        return new PendingJobView(
                pending.getId(),
                pending.getProject().getId(),
                pending.getState(),
                UserInfo.of(pending.getRequestedBy(), users),
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
