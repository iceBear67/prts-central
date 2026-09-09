package io.ib67.prts.pending;

import io.ib67.prts.job.JobConfig;
import io.ib67.prts.job.entity.JobRequest;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.user.UserContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the lifecycle, queuing, and state transitions of pending jobs.
 */
@ApplicationScoped
public class PendingJobService {

    @Inject
    ProjectService projectService;
    @Inject
    UserContext userContext;
    @Inject
    JobConfig jobConfig;

    /**
     * Enqueues an authorized job request to be dispatched when a worker is available.
     */
    public PendingJob enqueue(UUID projectId, JobRequest authorized) {
        var user = userContext.get();
        if (user == null) {
            throw new IllegalStateException("enqueue needs a requester; no user on this context");
        }
        var config = jobConfig.pending();
        return QuarkusTransaction.requiringNew().call(() -> {
            var project = projectService.require(projectId);
            var active = PendingJob.countActive(projectId);
            if (active >= config.maxPerProject()) {
                throw new ClientErrorException(
                        "too many queued jobs in this project: " + active + " >= " + config.maxPerProject(),
                        Response.Status.CONFLICT);
            }
            var now = Instant.now();
            var pending = PendingJob.builder()
                    .project(project)
                    .requestedBy(user.getId())
                    .request(authorized)
                    .state(PendingJobState.QUEUED)
                    .expiresAt(now.plus(config.ttl()))
                    .nextAttemptAt(now)
                    .build();
            pending.persist();
            return pending;
        });
    }

    public Optional<PendingJob> findInProject(UUID projectId, UUID pendingId) {
        return PendingJob.<PendingJob>findByIdOptional(pendingId)
                .filter(pending -> pending.getProject().getId().equals(projectId));
    }

    /**
     * Cancels a queued pending job if it has not yet started dispatching.
     */
    @Transactional
    public PendingJob cancel(UUID projectId, UUID pendingId) {
        var pending = PendingJob.<PendingJob>findById(pendingId, LockModeType.PESSIMISTIC_WRITE);
        if (pending == null || !pending.getProject().getId().equals(projectId)) {
            throw new NotFoundException("no such pending job in project " + projectId + ": " + pendingId);
        }
        if (pending.getState() != PendingJobState.QUEUED) {
            throw new ClientErrorException(
                    "pending job is " + pending.getState() + ": " + pendingId, Response.Status.CONFLICT);
        }
        pending.setState(PendingJobState.CANCELLED);
        return pending;
    }

    /**
     * Claims up to {@code limit} due pending jobs, transitioning them to DISPATCHING state.
     */
    @Transactional
    public List<Attempt> claimDue(int limit) {
        return PendingJob.listDue(Instant.now(), limit).stream()
                .map(pending -> {
                    pending.setState(PendingJobState.DISPATCHING);
                    return new Attempt(
                            pending.getId(),
                            pending.getProject().getId(),
                            pending.getRequestedBy(),
                            pending.getRequest());
                })
                .toList();
    }

    /** Value object representing a claimed pending job ready for a dispatch attempt. */
    public record Attempt(UUID id, UUID projectId, UUID requestedBy, JobRequest request) {
        public Attempt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(requestedBy, "requestedBy");
            Objects.requireNonNull(request, "request");
        }
    }

    @Transactional
    public void markDispatched(UUID pendingId, UUID jobId) {
        inFlight(pendingId).ifPresent(pending -> {
            pending.setAttempts(pending.getAttempts() + 1);
            pending.setState(PendingJobState.DISPATCHED);
            pending.setJobId(jobId);
            pending.setLastError(null);
        });
    }

    /** Requeues a pending job with backoff after an unplaced attempt. */
    @Transactional
    public void requeue(UUID pendingId, String reason) {
        inFlight(pendingId).ifPresent(pending -> {
            var attempts = pending.getAttempts() + 1;
            pending.setAttempts(attempts);
            pending.setState(PendingJobState.QUEUED);
            pending.setNextAttemptAt(Instant.now().plus(backoff(attempts)));
            pending.setLastError(reason);
        });
    }

    @Transactional
    public void markFailed(UUID pendingId, String reason) {
        inFlight(pendingId).ifPresent(pending -> {
            pending.setAttempts(pending.getAttempts() + 1);
            pending.setState(PendingJobState.FAILED);
            pending.setLastError(reason);
        });
    }

    /** Finds a pending job that is currently in the DISPATCHING state. */
    private static Optional<PendingJob> inFlight(UUID pendingId) {
        return PendingJob.<PendingJob>findByIdOptional(pendingId)
                .filter(pending -> pending.getState() == PendingJobState.DISPATCHING);
    }

    @Transactional
    public int expireOverdue() {
        return PendingJob.expireOverdue(Instant.now());
    }

    @Transactional
    public int resetDispatching() {
        return PendingJob.resetDispatching();
    }

    private Duration backoff(int attempts) {
        var config = jobConfig.pending();
        var doubled = config.backoff().multipliedBy(1L << Math.min(attempts - 1, 32));
        return doubled.compareTo(config.maxBackoff()) > 0 ? config.maxBackoff() : doubled;
    }
}
