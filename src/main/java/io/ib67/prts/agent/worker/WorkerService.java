package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class WorkerService {
    private static final Logger LOG = Logger.getLogger(WorkerService.class);

    private final Map<UUID, RegisteredWorker> activeWorkers = new ConcurrentHashMap<>();
    private final WorkerScheduler scheduler = new WorkerScheduler(activeWorkers);
    private final ScheduledExecutorService pendingTick = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "prts-pending-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void startPendingDispatch() {
        pendingTick.scheduleWithFixedDelay(() -> {
            try {
                scheduler.dispatchPending();
            } catch (RuntimeException e) {
                LOG.error("pending dispatch failed", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopPendingDispatch() {
        pendingTick.shutdownNow();
    }

    public Map<UUID, RegisteredWorker> getActiveWorkers() {
        return Collections.unmodifiableMap(activeWorkers);
    }

    public Optional<RegisteredWorker> getWorker(UUID id) {
        return Optional.ofNullable(activeWorkers.get(id));
    }

    boolean registerWorker(UUID id, RegisteredWorker registeredWorker) {
        QuarkusTransaction.requiringNew().run(() ->
                io.ib67.prts.agent.worker.entity.Worker.upsert(id, registeredWorker.getName()));
        return activeWorkers.putIfAbsent(id, registeredWorker) == null;
    }

    void unregisterWorker(UUID id) {
        scheduler.onWorkerRemoved(id);
        var worker = activeWorkers.remove(id);
        if (worker != null) {
            worker.getRpc().failAll(new IllegalStateException("worker disconnected"));
        }
    }

    boolean updateInfo(UUID id, @Nullable RegisteredWorker.Info info) {
        var worker = activeWorkers.get(id);
        if (worker == null) {
            return false;
        }
        worker.setInfo(info);
        return true;
    }

    boolean onJobCreated(UUID workerId, UUID requestId, UUID jobId) {
        var worker = activeWorkers.get(workerId);
        if (worker == null) {
            return false;
        }
        scheduler.onCreateAcknowledged(workerId);
        worker.getRpc().completeCreate(requestId, jobId);
        return true;
    }

    /**
     * Places the job on a live worker, or queues it. Returns the pending-row id when queued,
     * otherwise {@code null}.
     */
    public UUID schedule(ResourceClass resourceClass, JobSpec spec) {
        var required = requireResourceClass(resourceClass);
        if (scheduler.schedule0(required, spec)) {
            return null;
        }
        return enqueue(required, spec);
    }

    private ResourceClass requireResourceClass(ResourceClass resourceClass) {
        if (resourceClass == null || resourceClass.getName() == null) {
            throw new IllegalArgumentException("resource class name is required");
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            var found = ResourceClass.<ResourceClass>findById(resourceClass.getName());
            if (found == null) {
                throw new NoSuchElementException("no such resource class: " + resourceClass.getName());
            }
            return found;
        });
    }

    private UUID enqueue(ResourceClass resourceClass, JobSpec spec) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var managed = ResourceClass.getEntityManager()
                    .getReference(ResourceClass.class, resourceClass.getName());
            var pending = PendingJob.builder()
                    .resourceClass(managed)
                    .spec(spec)
                    .build();
            pending.persistAndFlush();
            return pending.getId();
        });
    }
}
