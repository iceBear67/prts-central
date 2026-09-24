package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.entity.Job;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.eventbus.EventBus;
import jakarta.annotation.Nullable;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles worker selection and concurrency locks for job scheduling.
 *
 * <p>Every database touch here opens its own {@code requiringNew} transaction. Placement runs on the
 * pending-job dispatcher thread, which carries neither a transaction nor a CDI request context, so
 * nothing is inherited; and the placement RPC sits between the reads and the claim, so they cannot
 * share one either.
 */
final class WorkerScheduler {
    private static final Logger LOG = Logger.getLogger(WorkerScheduler.class);

    private final Map<UUID, Worker> workers;
    private final EventBus bus;
    private final WorkerService service;
    private final Set<UUID> lockedWorkers = ConcurrentHashMap.newKeySet();

    WorkerScheduler(Map<UUID, Worker> workers, EventBus bus, WorkerService service) {
        this.workers = workers;
        this.bus = bus;
        this.service = service;
        bus.consumer(WorkerEvent.OFFLINE, m -> onWorkerRemoved((UUID) m.body()));
    }

    void onWorkerRemoved(UUID id) {
        lockedWorkers.remove(id);
    }

    /**
     * Attempts to place a job on an eligible worker.
     *
     * @return null on success or if the job has already finished; otherwise an error message explaining why placement failed
     * @throws IllegalStateException if the worker's session ended before the placement was recorded
     */
    @Nullable
    String schedule0(UUID jobId, ResourceClass required, JobSpec spec) {
        if (!isSchedulable(jobId)) {
            return null;
        }
        var lockName = spec.lock();
        if (!lockName.isEmpty() && !acquireLock(lockName, jobId)) {
            return "another job holds the lock " + lockName;
        }
        var dispatched = false;
        try {
            var selected = selectAndLock(required, spec);
            if (selected.isEmpty()) {
                return "no available worker can run this job";
            }
            var pick = selected.get();
            announce(jobId, pick.workerId());
            service.createJob(pick.workerId(), jobId, spec, required)
                    .whenComplete((accepted, throwable) -> {
                        unlock(pick.workerId());
                        if (throwable != null) {
                            cancelQuietly(pick, jobId);
                        }
                    }).join();
            if (!claimJob(jobId, pick.workerId())) {
                // Job was cancelled while dispatching; cancel on the worker.
                cancelQuietly(pick, jobId);
                return null;
            }
            // A session leaves the roster before its jobs are looked up to be failed, and the claim
            // above committed before this read: either that lookup sees the claim, or this sees the
            // session gone. The worker stopped the job itself when its connection dropped.
            if (workers.get(pick.workerId()) != pick.worker()) {
                throw new IllegalStateException("worker " + pick.workerId()
                        + " disconnected before the placement of job " + jobId + " was recorded");
            }
            dispatched = true;
            return null;
        } finally {
            if (!dispatched && !lockName.isEmpty()) {
                releaseLock(jobId);
            }
        }
    }

    /**
     * Announces which worker the job goes to, before it is sent: the worker may report on the job as
     * soon as it has it, before {@link #claimJob} sets {@code Job.worker}.
     */
    private void announce(UUID jobId, UUID workerId) {
        bus.publish(WorkerEvent.ASSIGNED, new WorkerEvent.Assignment(jobId, workerId));
    }

    private boolean isSchedulable(UUID jobId) {
        return QuarkusTransaction.requiringNew().call(() -> Job.<Job>findByIdOptional(jobId)
                .filter(job -> !job.isCompleted())
                .isPresent());
    }

    /**
     * Assigns the worker to the job in the database. Returns false if the job is already completed.
     *
     * <p>Placement is also where {@code startedAt} is stamped: the worker has accepted the job by
     * this point, and nothing later in the protocol reports a start of its own.
     */
    private boolean claimJob(UUID jobId, UUID workerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var job = Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE);
            if (job == null || job.isCompleted()) {
                return false;
            }
            job.setWorker(workerId);
            job.setStartedAt(Instant.now());
            return true;
        });
    }

    private boolean acquireLock(String lockName, UUID jobId) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> JobLock.tryAcquire(lockName, jobId));
        } catch (RuntimeException e) {
            LOG.debugf(e, "could not take lock %s for job %s", lockName, jobId);
            return false;
        }
    }

    private void releaseLock(UUID jobId) {
        try {
            QuarkusTransaction.requiringNew().run(() -> JobLock.releaseBy(jobId));
        } catch (RuntimeException e) {
            LOG.errorf(e, "failed to release the lock held by job %s", jobId);
        }
    }

    private void cancelQuietly(Selection pick, UUID jobId) {
        try {
            // Not joined: a placement that timed out completes on the timeout scheduler, which must
            // not be held for the length of another call.
            service.cancelJob(pick.workerId(), jobId).whenComplete((told, failure) -> {
                if (failure != null) {
                    LOG.errorf(failure, "job %s was cancelled but worker %s could not be told",
                            jobId, pick.workerId());
                }
            });
        } catch (RuntimeException e) {
            LOG.errorf(e, "job %s was cancelled but worker %s could not be told", jobId, pick.workerId());
        }
    }

    private Optional<Selection> selectAndLock(ResourceClass required, JobSpec spec) {
        var allowed = workersForVolumes(spec);
        if (allowed.isEmpty()) {
            return Optional.empty();
        }
        synchronized (this) {
            var selected = select(required, allowed);
            selected.ifPresent(pick -> lockedWorkers.add(pick.workerId()));
            return selected;
        }
    }

    void unlock(UUID workerId) {
        lockedWorkers.remove(workerId);
    }

    /**
     * Selects a connected, enabled worker to host a new volume, balancing volume counts across workers.
     */
    Optional<UUID> selectVolumeHost() {
        var candidates = workers.entrySet().stream()
                .filter(entry -> !entry.getValue().isDisabled())
                .map(Map.Entry::getKey)
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var hosted = QuarkusTransaction.requiringNew().call(() -> WorkerVolume.countByWorkers(candidates));
        return candidates.stream()
                .min(Comparator
                        .comparingLong((UUID id) -> hosted.getOrDefault(id, 0L))
                        .thenComparing(Comparator.naturalOrder()));
    }

    private Optional<Selection> select(ResourceClass required, Set<UUID> allowed) {
        return workers.entrySet().stream()
                .filter(entry -> isEligible(entry.getKey(), entry.getValue(), required, allowed))
                .min(Comparator
                        .comparingInt((Map.Entry<UUID, Worker> entry) -> entry.getValue().pendingJobCount())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new Selection(entry.getKey(), entry.getValue()));
    }

    private boolean isEligible(UUID id, Worker worker, ResourceClass required, Set<UUID> allowed) {
        return allowed.contains(id)
                && !worker.isDisabled()
                && !lockedWorkers.contains(id)
                && capacityFits(worker.getInfo(), required);
    }

    /**
     * Finds workers capable of mounting the requested volumes. Returns all workers if no volumes are requested.
     */
    private Set<UUID> workersForVolumes(JobSpec spec) {
        var volumes = spec.volumes();
        if (volumes.isEmpty()) {
            return workers.keySet();
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            var rows = WorkerVolume.listByIds(volumes.keySet());
            if (rows.size() != volumes.size()) {
                return Set.of();
            }
            UUID owner = null;
            for (var row : rows) {
                if (!row.getState().isUsable()) {
                    return Set.of();
                }
                var need = volumes.get(row.getId());
                if (need != null && row.remaining() < need.sizeLimit()) {
                    return Set.of();
                }
                var workerId = row.getWorker().getId();
                if (owner == null) {
                    owner = workerId;
                } else if (!owner.equals(workerId)) {
                    return Set.of();
                }
            }
            return owner == null ? Set.of() : Set.of(owner);
        });
    }

    private static boolean capacityFits(@Nullable Worker.Info available, ResourceClass required) {
        if (available == null || available.getCapacity() == null) {
            return true;
        }
        var capacity = available.getCapacity();
        return capacity.getNumCpus() >= required.getNumCpus()
                && capacity.getNumMemories() >= required.getMemCount()
                && capacity.getNumDisks() >= required.getDiskSize();
    }

    record Selection(UUID workerId, Worker worker) {
        Selection {
            Objects.requireNonNull(workerId, "id");
            Objects.requireNonNull(worker, "worker");
        }
    }
}
