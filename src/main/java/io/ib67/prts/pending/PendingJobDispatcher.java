package io.ib67.prts.pending;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobService;
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
 * Drains the queue by acting as a client of {@link JobService}: it holds no scheduling knowledge of
 * its own and learns that a job could not be placed the same way the endpoint does.
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
    JobService jobService;
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

    private void attempt(PendingJobService.Attempt attempt) {
        try {
            var created = jobService.createPreAuthorized(
                    attempt.projectId(), attempt.templateId(), attempt.override(), attempt.resourceClass());
            if (created.scheduled()) {
                pendingJobService.markDispatched(attempt.id(), created.job().getId());
            } else {
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
