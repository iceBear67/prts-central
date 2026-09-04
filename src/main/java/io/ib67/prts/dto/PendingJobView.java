package io.ib67.prts.dto;

import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * @param request      what will be posted on the entry's behalf. Published to anyone who may read the
 *                     queue, unlike {@link JobView#createRequest()}, which is gated on being able to
 *                     re-run it: here the request <em>is</em> the entry, and it was authorized once
 *                     already, for {@link #requestedBy}.
 * @param nextAttemptAt when the entry is due again, or {@code null} once it stops moving.
 * @param jobId        the job the entry became, set with {@link PendingJobState#DISPATCHED}.
 */
public record PendingJobView(
        UUID id,
        UUID projectId,
        PendingJobState state,
        @Nullable UUID requestedBy,
        Instant createdAt,
        Instant expiresAt,
        @Nullable Instant nextAttemptAt,
        int attempts,
        @Nullable String lastError,
        @Nullable UUID jobId,
        CreateJobRequest request
) {
    /** Reads only what a detached entry carries — the project for its id alone, like {@link JobView}. */
    public static PendingJobView of(PendingJob pending) {
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
                CreateJobRequest.of(pending.getRequest()));
    }
}
