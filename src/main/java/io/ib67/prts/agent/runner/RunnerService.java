package io.ib67.prts.agent.runner;

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
public class RunnerService {
    private static final Logger LOG = Logger.getLogger(RunnerService.class);

    private final Map<UUID, Runner> activeRunners = new ConcurrentHashMap<>();
    private final RunnerScheduler scheduler = new RunnerScheduler(activeRunners);
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

    public Map<UUID, Runner> getActiveRunners() {
        return Collections.unmodifiableMap(activeRunners);
    }

    public Optional<Runner> getRunner(UUID id) {
        return Optional.ofNullable(activeRunners.get(id));
    }

    boolean registerRunner(UUID id, Runner runner) {
        return activeRunners.putIfAbsent(id, runner) == null;
    }

    void unregisterRunner(UUID id) {
        scheduler.onRunnerRemoved(id);
        var runner = activeRunners.remove(id);
        if (runner != null) {
            runner.getRpc().failAll(new IllegalStateException("runner disconnected"));
        }
    }

    boolean updateInfo(UUID id, @Nullable RunnerInfo info) {
        var runner = activeRunners.get(id);
        if (runner == null) {
            return false;
        }
        runner.setInfo(info);
        return true;
    }

    boolean onJobCreated(UUID runnerId, UUID requestId, UUID jobId) {
        var runner = activeRunners.get(runnerId);
        if (runner == null) {
            return false;
        }
        scheduler.onCreateAcknowledged(runnerId);
        runner.getRpc().completeCreate(requestId, jobId);
        return true;
    }

    /**
     * Places the job on a live runner, or queues it. Returns the pending-row id when queued,
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
