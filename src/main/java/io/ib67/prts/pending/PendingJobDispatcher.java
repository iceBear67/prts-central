package io.ib67.prts.pending;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.project.JobLauncher;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.JobConfig;
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
 * Background scheduler that periodically processes queued pending jobs and attempts to dispatch them.
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
    JobService jobService;
    @Inject
    WorkerService workerService;
    @Inject
    JobConfig jobConfig;

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

    // Package-private so a test can drive a single pass instead of waiting on the ticker.
    void tick() {
        try {
            pendingJobService.expireOverdue();
            // Skip processing if no workers are currently available to accept jobs.
            if (!workerService.hasSchedulableWorker()) {
                return;
            }
            for (var attempt : pendingJobService.claimDue(jobConfig.pending().batch())) {
                attempt(attempt);
            }
        } catch (Throwable t) {
            LOG.error("pending job dispatcher tick failed", t);
        }
    }

    /**
     * Attempts to dispatch a pending job using {@link JobLauncher}.
     */
    private void attempt(PendingJobService.Attempt attempt) {
        try {
            var created = jobLauncher.launch(
                    attempt.projectId(), attempt.requestedBy(), attempt.request(),
                    JobLauncher.PRE_AUTHORIZED);
            if (created.scheduled()) {
                pendingJobService.markDispatched(attempt.id(), created.job().getId());
            } else {
                // Discard the unplaced job record and requeue the pending entry with backoff.
                jobService.discard(created.job().getId());
                pendingJobService.requeue(attempt.id(), "no worker could take the job yet");
            }
        } catch (RuntimeException e) {
            // Unrecoverable failure; mark the pending job as failed.
            LOG.infof("pending job %s gave up: %s", attempt.id(), e.toString());
            pendingJobService.markFailed(attempt.id(), e.toString());
        }
    }
}
