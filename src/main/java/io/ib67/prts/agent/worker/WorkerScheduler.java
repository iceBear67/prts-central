package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.entity.PendingJob;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.project.Job;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduling overlay on {@link WorkerService}'s worker map: exclusive create-locks and
 * draining of queued {@link PendingJob} rows. The map itself stays on the service.
 */
final class WorkerScheduler {
    private static final Logger LOG = Logger.getLogger(WorkerScheduler.class);

    private final Map<UUID, RegisteredWorker> workers;
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inFlightPending = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();

    WorkerScheduler(Map<UUID, RegisteredWorker> workers) {
        this.workers = workers;
    }

    void onWorkerRemoved(UUID id) {
        locked.remove(id);
    }

    void onCreateAcknowledged(UUID workerId) {
        unlock(workerId);
    }

    void dispatchPending() {
        List<PendingJob> pending;
        try {
            pending = QuarkusTransaction.requiringNew().call(() -> {
                var rows = PendingJob.listFifo();
                rows.forEach(row -> {
                    var klass = row.getResourceClass();
                    if (klass == null || klass.getName() == null || row.getSpec() == null
                            || row.getJob() == null) {
                        throw new IllegalStateException("pending job is incomplete: " + row.getId());
                    }
                });
                return List.copyOf(rows);
            });
        } catch (RuntimeException e) {
            LOG.error("failed to load pending jobs", e);
            return;
        }
        for (var job : pending) {
            if (!inFlightPending.add(job.getId())) {
                continue;
            }
            try {
                if (schedule0(job.getJob().getId(), job.getResourceClass(), job.getSpec())) {
                    QuarkusTransaction.requiringNew().run(() -> PendingJob.deleteById(job.getId()));
                }
            } catch (RuntimeException e) {
                LOG.errorf(e, "failed to dispatch pending job %s", job.getId());
            } finally {
                inFlightPending.remove(job.getId());
            }
        }
    }

    /**
     * Tries to place {@code jobId} on a live worker. {@code false} means it could not be placed
     * right now — no eligible worker, or its {@link JobLock} is held — and the caller should keep it
     * queued. {@code true} means the job needs no further dispatch, which also covers a job that
     * went terminal in the meantime. RPC failure after a worker is locked is thrown (and unlocked).
     */
    boolean schedule0(UUID jobId, ResourceClass required, JobSpec spec) {
        if (!isSchedulable(jobId)) {
            return true;
        }
        var lockName = spec == null ? null : spec.normalizedLock();
        if (lockName != null && !acquireLock(lockName, jobId)) {
            return false;
        }
        var dispatched = false;
        try {
            var selected = selectAndLock(required, spec);
            if (selected.isEmpty()) {
                return false;
            }
            var pick = selected.get();
            try {
                pick.registeredWorker().getRpc().createJob(jobId, spec, required);
            } catch (RuntimeException e) {
                unlock(pick.id());
                throw e;
            }
            if (!claimJob(jobId, pick.id())) {
                // Cancelled while the worker was starting it: undo rather than leak the container.
                cancelQuietly(pick, jobId);
                return true;
            }
            dispatched = true;
            return true;
        } finally {
            if (!dispatched && lockName != null) {
                releaseLock(jobId);
            }
        }
    }

    /** {@code false} once the job is gone or terminal, so there is nothing left to dispatch. */
    private boolean isSchedulable(UUID jobId) {
        return QuarkusTransaction.requiringNew().call(() -> Job.<Job>findByIdOptional(jobId)
                .filter(job -> !job.isCompleted())
                .isPresent());
    }

    /**
     * Records the worker that took the job. {@code false} means the job went terminal (a cancel
     * landed) while we were handing it over.
     */
    private boolean claimJob(UUID jobId, UUID workerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var job = Job.<Job>findById(jobId, LockModeType.PESSIMISTIC_WRITE);
            if (job == null || job.isCompleted()) {
                return false;
            }
            job.setWorker(workerId);
            return true;
        });
    }

    /**
     * {@code false} means another live job holds the lock. A failed insert lost a race with a
     * concurrent dispatch, which is also "busy": the caller stays queued and retries.
     */
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
            pick.registeredWorker().getRpc().cancelJob(jobId);
        } catch (RuntimeException e) {
            LOG.errorf(e, "job %s was cancelled but worker %s could not be told", jobId, pick.id());
        }
    }

    private Optional<Selection> selectAndLock(ResourceClass required, JobSpec spec) {
        var allowed = workersForVolumes(spec);
        if (allowed != null && allowed.isEmpty()) {
            return Optional.empty();
        }
        synchronized (lock) {
            var selected = select(required, allowed);
            selected.ifPresent(pick -> locked.add(pick.id()));
            return selected;
        }
    }

    void unlock(UUID workerId) {
        locked.remove(workerId);
    }

    private Optional<Selection> select(ResourceClass required, @Nullable Set<UUID> allowed) {
        return workers.entrySet().stream()
                .filter(entry -> allowed == null || allowed.contains(entry.getKey()))
                .filter(entry -> !locked.contains(entry.getKey()))
                .filter(entry -> capacityFits(entry.getValue().getInfo(), required))
                .min(Comparator
                        .comparingInt((Map.Entry<UUID, RegisteredWorker> entry) -> entry.getValue().pendingJobCount())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new Selection(entry.getKey(), entry.getValue()));
    }

    /**
     * {@code null} means any live worker. An empty set means the volume set cannot be placed.
     */
    @Nullable
    private Set<UUID> workersForVolumes(JobSpec spec) {
        var volumes = spec == null ? null : spec.volumes();
        if (volumes == null || volumes.isEmpty()) {
            return null;
        }
        if (volumes.containsKey(null)) {
            return Set.of();
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            var rows = WorkerVolume.listByIds(volumes.keySet());
            if (rows.size() != volumes.size()) {
                return Set.of();
            }
            UUID owner = null;
            for (var row : rows) {
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

    private static boolean capacityFits(@Nullable RegisteredWorker.Info available, ResourceClass required) {
        if (available == null || available.getCapacity() == null) {
            return true;
        }
        var capacity = available.getCapacity();
        return capacity.getNumCpus() >= required.getNumCpus()
                && capacity.getNumMemories() >= required.getMemCount()
                && capacity.getNumDisks() >= required.getDiskSize();
    }

    record Selection(UUID id, RegisteredWorker registeredWorker) {
    }
}
