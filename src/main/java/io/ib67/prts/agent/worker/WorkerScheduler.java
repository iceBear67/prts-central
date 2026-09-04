package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.project.entity.Job;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduling overlay on {@link WorkerService}'s worker map: worker selection and exclusive
 * create-locks. The map itself stays on the service.
 */
final class WorkerScheduler {
    private static final Logger LOG = Logger.getLogger(WorkerScheduler.class);

    private final Map<UUID, RegisteredWorker> workers;
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
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

    /**
     * Tries to place {@code jobId} on a live worker. Returns why it could not be placed — no
     * eligible worker, or its {@link JobLock} is held — for the caller to refuse the job with; there
     * is no queue. {@code null} means nothing more is needed, which also covers a job that went
     * terminal in the meantime. RPC failure after a worker is locked is thrown (and unlocked).
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
            try {
                pick.registeredWorker().getRpc().createJob(jobId, spec, required);
            } catch (RuntimeException e) {
                unlock(pick.id());
                // The offer timed out rather than being refused, so the worker may have started the
                // job anyway. Tell it to stop before this job is failed, or it runs on past the lock
                // this attempt is about to release.
                cancelQuietly(pick, jobId);
                throw e;
            }
            if (!claimJob(jobId, pick.id())) {
                // Cancelled while the worker was starting it: undo rather than leak the container.
                cancelQuietly(pick, jobId);
                return null;
            }
            dispatched = true;
            return null;
        } finally {
            if (!dispatched && !lockName.isEmpty()) {
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
     * concurrent dispatch, which is also "busy": either way the job is refused.
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
        if (allowed.isEmpty()) {
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

    private Optional<Selection> select(ResourceClass required, Set<UUID> allowed) {
        return workers.entrySet().stream()
                .filter(entry -> allowed.contains(entry.getKey()))
                .filter(entry -> !locked.contains(entry.getKey()))
                .filter(entry -> capacityFits(entry.getValue().getInfo(), required))
                .min(Comparator
                        .comparingInt((Map.Entry<UUID, RegisteredWorker> entry) -> entry.getValue().pendingJobCount())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new Selection(entry.getKey(), entry.getValue()));
    }

    /**
     * The workers that could hold the spec's volumes — every live one when it mounts none. An empty
     * set means the volumes cannot be placed, which is why "no constraint" is the full set and not
     * an empty one.
     */
    private Set<UUID> workersForVolumes(JobSpec spec) {
        var volumes = spec.volumes();
        if (volumes.isEmpty()) {
            return workers.keySet();
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
        Selection {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(registeredWorker, "registeredWorker");
        }
    }
}
