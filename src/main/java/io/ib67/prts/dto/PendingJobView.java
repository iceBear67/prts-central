package io.ib67.prts.dto;

import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * @param request      what will be posted on the entry's behalf, or {@code null} when the caller may
 *                     not post it. Gated exactly like {@link JobView#createRequest()} — an entry is a
 *                     create that has not happened yet, so seeing the request takes what making one
 *                     takes. Not published to every reader of the queue: the request carries the
 *                     override, whose fields are individually gated on the way in.
 * @param nextAttemptAt when the entry is due again, or {@code null} once it stops moving.
 * @param jobId        the job the entry became, set with {@link PendingJobState#DISPATCHED}.
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
    /**
     * Reads only what a detached entry carries — the project for its id alone, like {@link JobView}.
     * {@code request} is passed in for the same reason {@code createRequest} is there: whether the
     * caller may see it is an authorization question, answered at the endpoint.
     */
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
