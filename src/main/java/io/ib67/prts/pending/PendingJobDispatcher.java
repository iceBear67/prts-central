package io.ib67.prts.pending;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobLauncher;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drains the queue by acting as a client of {@link JobLauncher}: it holds no scheduling knowledge of
 * its own, and being the only caller that launches, it owns what an unplaceable job leaves behind.
 */
@ApplicationScoped
public class PendingJobDispatcher {
    private static final Logger LOG = Logger.getLogger(PendingJobDispatcher.class);

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "prts-pending-job-dispatcher");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    PendingJobService pendingJobService;
    @Inject
    JobLauncher jobLauncher;
    @Inject
    WorkerService workerService;
    @Inject
    JobConfig jobConfig;

    // On startup rather than @PostConstruct: nothing injects this bean, so it would never be created.
    void start(@Observes StartupEvent event) {
        try {
            var requeued = pendingJobService.resetDispatching();
            if (requeued > 0) {
                LOG.infof("requeued %s pending job(s) a previous run left dispatching", requeued);
            }
        } catch (RuntimeException e) {
            LOG.error("could not requeue pending jobs left dispatching", e);
        }
        var interval = jobConfig.pending().interval().toMillis();
        ticker.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    private void tick() {
        try {
            pendingJobService.expireOverdue();
            // An attempt persists a job before it finds out there is nowhere to put it, so with no
            // worker at all there is nothing to gain and a row to write per entry per tick.
            if (workerService.getActiveWorkers().isEmpty()) {
                return;
            }
            for (var attempt : pendingJobService.claimDue(jobConfig.pending().batch())) {
                attempt(attempt);
            }
        } catch (Throwable t) {
            // Letting this out would cancel the schedule for the rest of the process's life.
            LOG.error("pending job dispatcher tick failed", t);
        }
    }

    /**
     * Replays a request {@link PendingJobService#enqueue} already had cleared, which is why the gate
     * is {@link JobLauncher#PRE_AUTHORIZED}: only the per-field override rules are taken as settled,
     * and this is where that is vouched for. Everything else — the template's project, the volume
     * rule, the class the spec may name — the launcher checks again against the state of now.
     */
    private void attempt(PendingJobService.Attempt attempt) {
        try {
            var created = jobLauncher.launch(
                    attempt.projectId(), attempt.requestedBy(), attempt.request(),
                    JobLauncher.PRE_AUTHORIZED);
            if (created.scheduled()) {
                pendingJobService.markDispatched(attempt.id(), created.job().getId());
            } else {
                // The entry is the thing that waits, so the job it made has nothing to say and is
                // undone; the next attempt makes another. See TODO.md on what a crash here leaves.
                jobLauncher.discard(created.job().getId());
                pendingJobService.requeue(attempt.id(), "no worker could take the job yet");
            }
        } catch (RuntimeException e) {
            // The request stopped working — a deleted template, a removed volume, a hand-over that
            // failed. Nothing here will fix it and the requester is not around to be asked.
            LOG.infof("pending job %s gave up: %s", attempt.id(), e.toString());
            pendingJobService.markFailed(attempt.id(), e.toString());
        }
    }
}
