package io.ib67.prts.pending;

import io.ib67.prts.dto.UserInfo;
import io.ib67.prts.dto.job.PendingJobView;
import io.ib67.prts.dto.request.CreateJobRequest;
import io.ib67.prts.job.JobAccess;
import io.ib67.prts.job.JobConfig;
import io.ib67.prts.job.JobStatus;
import io.ib67.prts.job.entity.JobRequest;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
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
    @Inject
    JobAccess jobAccess;

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

    /**
     * Lists one window of the project's undispatched queue entries, narrowed to a task and a status
     * where either is given. The scope is validated by the job half of the same listing.
     *
     * <p>A status belonging only to a placed job ({@code RUNNING}, {@code SUCCESS}, …) selects no
     * entry, which is not the same as selecting every one.
     */
    public List<PendingJob> listUnplaced(
            UUID projectId, @Nullable UUID taskId, @Nullable JobStatus status, int limit) {
        var state = status == null ? null : status.queued();
        return status != null && state == null
                ? List.of()
                : PendingJob.listUnplaced(projectId, taskId, state, limit);
    }

    /** What {@link #listUnplaced} would return unwindowed. */
    public long countUnplaced(UUID projectId, @Nullable UUID taskId, @Nullable JobStatus status) {
        var state = status == null ? null : status.queued();
        return status != null && state == null ? 0 : PendingJob.countUnplaced(projectId, taskId, state);
    }

    public Optional<PendingJob> findInProject(UUID projectId, UUID pendingId) {
        return PendingJob.<PendingJob>findByIdOptional(pendingId)
                .filter(pending -> pending.getProject().getId().equals(projectId));
    }

    /** Builds the view for a queued job, including the payload only when permitted. */
    public PendingJobView viewOf(UUID projectId, PendingJob pending) {
        return viewOf(List.of(pending), jobAccess.mayCreate(projectId)).getFirst();
    }

    /** Builds views for multiple queued jobs, batch-resolving requesters. */
    public List<PendingJobView> viewOf(UUID projectId, List<PendingJob> queued) {
        return viewOf(queued, jobAccess.mayCreate(projectId));
    }

    private List<PendingJobView> viewOf(List<PendingJob> queued, boolean withRequest) {
        var users = User.mapByIds(queued.stream().map(PendingJob::getRequestedBy).distinct().toList());
        return queued.stream()
                .map(pending -> new PendingJobView(
                        pending.getId(),
                        pending.getProject().getId(),
                        pending.getState(),
                        UserInfo.of(pending.getRequestedBy(), users.get(pending.getRequestedBy())),
                        pending.getRequest().resourceClass(),
                        pending.getCreatedAt(),
                        pending.getExpiresAt(),
                        pending.getState().isSettled() ? null : pending.getNextAttemptAt(),
                        pending.getAttempts(),
                        pending.getLastError(),
                        pending.getJobId(),
                        withRequest ? CreateJobRequest.of(pending.getRequest()) : null))
                .toList();
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

    /**
     * Records the created job for a dispatched pending job.
     *
     * <p>The entry is claimed in one transaction and launched outside it, so concurrent bulk cancellation
     * (e.g. project archival, deletion, or task teardown) may have cancelled the row during launch.
     *
     * @return false if the pending job is no longer in {@code DISPATCHING} state
     */
    @Transactional
    public boolean markDispatched(UUID pendingId, UUID jobId) {
        var pending = inFlight(pendingId);
        pending.ifPresent(entry -> {
            entry.setAttempts(entry.getAttempts() + 1);
            entry.setState(PendingJobState.DISPATCHED);
            entry.setJobId(jobId);
            entry.setLastError(null);
        });
        return pending.isPresent();
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
