package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.entity.PendingJob;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.project.Job;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.JobState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @Inject
    JobService jobService;

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
            // Throwable: anything escaping here cancels the schedule and ends dispatch for good.
            try {
                scheduler.dispatchPending();
            } catch (Throwable e) {
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

    /** Newest connection wins: a half-open socket must not lock out the reconnect that replaces it. */
    void registerWorker(UUID id, RegisteredWorker registeredWorker) {
        QuarkusTransaction.requiringNew().run(() ->
                io.ib67.prts.agent.worker.entity.Worker.upsert(id, registeredWorker.getName()));
        var displaced = activeWorkers.put(id, registeredWorker);
        if (displaced != null) {
            scheduler.onWorkerRemoved(id);
            displaced.getRpc().failAll(new IllegalStateException("worker re-registered on a new connection"));
        }
    }

    /** Only the connection that owns the session may end it, or a superseded socket's close wins. */
    void unregisterWorker(UUID id, WebSocketConnection connection) {
        var worker = activeWorkers.get(id);
        if (worker == null || !worker.getRpc().isFor(connection) || !activeWorkers.remove(id, worker)) {
            return;
        }
        scheduler.onWorkerRemoved(id);
        worker.getRpc().failAll(new IllegalStateException("worker disconnected"));
        failJobsOf(id);
    }

    /** Nobody will report on these now, and a non-terminal job holds its {@link JobLock} forever. */
    private void failJobsOf(UUID workerId) {
        try {
            var open = QuarkusTransaction.requiringNew()
                    .call(() -> Job.listOpenByWorker(workerId).stream().map(Job::getId).toList());
            for (var jobId : open) {
                QuarkusTransaction.requiringNew().run(() -> PendingJob.deleteByJob(jobId));
                jobService.applyState(jobId, JobState.FAILED);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot fail the jobs of disconnected worker %s", workerId);
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

    boolean onJobCreated(UUID workerId, UUID requestId) {
        var worker = activeWorkers.get(workerId);
        if (worker == null) {
            return false;
        }
        scheduler.onCreateAcknowledged(workerId);
        worker.getRpc().completeCreate(requestId);
        return true;
    }

    /**
     * Places {@code jobId} on a live worker, or queues it. Returns the pending-row id when queued,
     * otherwise {@code null}. A queued job is retried by {@link #startPendingDispatch()} until a
     * worker is free and, when the spec names one, its {@link JobLock} is.
     */
    public UUID schedule(UUID jobId, ResourceClass resourceClass, JobSpec spec) {
        var required = requireResourceClass(resourceClass);
        if (scheduler.schedule0(jobId, required, spec)) {
            return null;
        }
        return enqueue(jobId, required, spec);
    }

    /**
     * Asks the worker running {@code jobId} to stop it. {@code false} means that worker is not
     * connected, so there was nobody to tell.
     */
    public boolean cancelJob(UUID workerId, UUID jobId) {
        var worker = activeWorkers.get(workerId);
        if (worker == null) {
            return false;
        }
        worker.getRpc().cancelJob(jobId);
        return true;
    }

    private ResourceClass requireResourceClass(ResourceClass resourceClass) {
        if (resourceClass == null || resourceClass.getName() == null) {
            throw new IllegalArgumentException("resource class name is required");
        }
        var key = resourceClass.key();
        return QuarkusTransaction.requiringNew().call(() -> {
            var found = ResourceClass.<ResourceClass>findById(key);
            if (found == null) {
                throw new NoSuchElementException("no such resource class: " + resourceClass.getName());
            }
            return found;
        });
    }

    private UUID enqueue(UUID jobId, ResourceClass resourceClass, JobSpec spec) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var entityManager = ResourceClass.getEntityManager();
            var managed = entityManager.getReference(ResourceClass.class, resourceClass.key());
            var pending = PendingJob.builder()
                    .resourceClass(managed)
                    .job(entityManager.getReference(Job.class, jobId))
                    .spec(spec)
                    .build();
            pending.persistAndFlush();
            return pending.getId();
        });
    }
}
