package io.ib67.prts.dto.job;

import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.pending.PendingJobState;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View representing a queued job pending dispatch. Built by {@code PendingJobService.viewOf}.
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

    @Override
    public String type() {
        return TYPE;
    }
}
