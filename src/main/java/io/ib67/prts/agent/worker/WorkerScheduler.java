package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
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

    private final Map<UUID, Worker> workers;
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inFlightPending = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();

    WorkerScheduler(Map<UUID, Worker> workers) {
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
                    if (klass == null || klass.getName() == null || row.getSpec() == null) {
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
                if (schedule0(job.getResourceClass(), job.getSpec())) {
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
     * Tries to place {@code spec} on a live worker. {@code false} means nobody eligible;
     * the caller should enqueue. RPC failure after a lock is taken is thrown (and unlocked).
     */
    boolean schedule0(ResourceClass required, JobSpec spec) {
        var selected = selectAndLock(required);
        if (selected.isEmpty()) {
            return false;
        }
        var pick = selected.get();
        try {
            pick.worker().getRpc().createJob(spec);
            return true;
        } catch (RuntimeException e) {
            unlock(pick.id());
            throw e;
        }
    }

    private Optional<Selection> selectAndLock(ResourceClass required) {
        synchronized (lock) {
            var selected = select(required);
            selected.ifPresent(pick -> locked.add(pick.id()));
            return selected;
        }
    }

    void unlock(UUID workerId) {
        locked.remove(workerId);
    }

    private Optional<Selection> select(ResourceClass required) {
        return workers.entrySet().stream()
                .filter(entry -> !locked.contains(entry.getKey()))
                .filter(entry -> capacityFits(entry.getValue().getInfo(), required))
                .min(Comparator
                        .comparingInt((Map.Entry<UUID, Worker> entry) -> entry.getValue().pendingJobCount())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new Selection(entry.getKey(), entry.getValue()));
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

    record Selection(UUID id, Worker worker) {
    }
}
