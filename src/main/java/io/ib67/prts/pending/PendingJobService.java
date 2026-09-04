package io.ib67.prts.pending;

import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobRequest;
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
import java.util.Optional;
import java.util.UUID;

/**
 * The queue in front of the job launcher. Its whole point is where the authorization happens: a
 * request is cleared by the endpoint, in the call the requester made, and replayed later by
 * {@link PendingJobDispatcher} through the very path that cleared it.
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
     * Keeps a request the caller has already had authorized, which is only sound from the request it
     * arrived in — what it may ask for has to be settled while the requester is still on the line,
     * since nothing the dispatcher does later could ask them. Taken on trust: the type cannot tell an
     * authorized request from any other.
     */
    public PendingJob enqueue(UUID projectId, JobRequest authorized) {
        var user = userContext.get();
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
                    .requestedBy(user == null ? null : user.getId())
                    .request(authorized)
                    .state(PendingJobState.QUEUED)
                    .expiresAt(now.plus(config.ttl()))
                    .nextAttemptAt(now)
                    .build();
            pending.persist();
            return pending;
        });
    }

    public List<PendingJob> listByProject(UUID projectId) {
        projectService.require(projectId);
        return PendingJob.listByProject(projectId);
    }

    /** Same rule as jobs: found by its own id, then kept only if it belongs to the project. */
    public Optional<PendingJob> findInProject(UUID projectId, UUID pendingId) {
        return PendingJob.<PendingJob>findByIdOptional(pendingId)
                .filter(pending -> pending.getProject().getId().equals(projectId));
    }

    /**
     * Drops a queued entry. Locked because {@link #claimDue} takes the same rows: an entry whose
     * attempt is already in flight cannot be called back, so cancelling it is a conflict rather than
     * a race — the job it produces is cancellable on its own.
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
     * Takes up to {@code limit} due entries off the queue for the dispatcher, marking them
     * {@link PendingJobState#DISPATCHING} so nothing else acts on them, and answering with what an
     * attempt needs — the entities do not outlive this transaction.
     */
    @Transactional
    public List<Attempt> claimDue(int limit) {
        return PendingJob.listDue(Instant.now(), limit).stream()
                .map(pending -> {
                    pending.setState(PendingJobState.DISPATCHING);
                    return new Attempt(pending.getId(), pending.getProject().getId(), pending.getRequest());
                })
                .toList();
    }

    /** A claimed entry, detached: the request as it was authorized, and nothing else. */
    public record Attempt(UUID id, UUID projectId, JobRequest request) {
    }

    @Transactional
    public void markDispatched(UUID pendingId, UUID jobId) {
        PendingJob.<PendingJob>findByIdOptional(pendingId).ifPresent(pending -> {
            pending.setAttempts(pending.getAttempts() + 1);
            pending.setState(PendingJobState.DISPATCHED);
            pending.setJobId(jobId);
            pending.setLastError(null);
        });
    }

    /** Back in line, later each time: an empty fleet must not cost a dispatch attempt per tick. */
    @Transactional
    public void requeue(UUID pendingId, String reason) {
        PendingJob.<PendingJob>findByIdOptional(pendingId).ifPresent(pending -> {
            var attempts = pending.getAttempts() + 1;
            pending.setAttempts(attempts);
            pending.setState(PendingJobState.QUEUED);
            pending.setNextAttemptAt(Instant.now().plus(backoff(attempts)));
            pending.setLastError(reason);
        });
    }

    @Transactional
    public void markFailed(UUID pendingId, String reason) {
        PendingJob.<PendingJob>findByIdOptional(pendingId).ifPresent(pending -> {
            pending.setAttempts(pending.getAttempts() + 1);
            pending.setState(PendingJobState.FAILED);
            pending.setLastError(reason);
        });
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
        // Shifted, not raised to a power: attempts is unbounded and the doubling has to stop before
        // the multiplication does.
        var doubled = config.backoff().multipliedBy(1L << Math.min(attempts - 1, 32));
        return doubled.compareTo(config.maxBackoff()) > 0 ? config.maxBackoff() : doubled;
    }
}
