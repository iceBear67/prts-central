package io.ib67.prts.dto.job;

import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a queued job pending dispatch.
 *
 * @param request       The create request payload, or null if hidden by caller permissions.
 * @param nextAttemptAt Timestamp when the next dispatch attempt is scheduled.
 * @param jobId         The resulting Job ID once dispatched.
 */
public record PendingJobView(
        UUID id,
        UUID projectId,
        PendingJobState state,
        UUID requestedBy,
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
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public static PendingJobView of(PendingJob pending, @Nullable CreateJobRequest request) {
        return new PendingJobView(
                pending.getId(),
                pending.getProject().getId(),
                pending.getState(),
                pending.getRequestedBy(),
                pending.getCreatedAt(),
                pending.getExpiresAt(),
                pending.getState().isSettled() ? null : pending.getNextAttemptAt(),
                pending.getAttempts(),
                pending.getLastError(),
                pending.getJobId(),
                request);
    }
}
