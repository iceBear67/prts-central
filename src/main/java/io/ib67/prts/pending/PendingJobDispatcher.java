package io.ib67.prts.pending;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.job.JobLauncher;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.JobConfig;
import io.ib67.prts.job.entity.Job;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background scheduler that periodically processes queued pending jobs and attempts to dispatch them.
 */
@ApplicationScoped
public class PendingJobDispatcher {
    private static final Logger LOG = Logger.getLogger(PendingJobDispatcher.class);
    private static final String CANCELLED_MID_DISPATCH = "queue entry cancelled while dispatching";

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

    // Visible for testing to trigger a single dispatch pass manually.
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
                if (!pendingJobService.markDispatched(attempt.id(), created.job().getId())) {
                    stopCancelled(attempt.id(), created.job().getId());
                }
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

    /**
     * Stops a job whose queue entry was cancelled while it was being launched.
     *
     * <p>The entry is claimed in one transaction and the job created in another, so a bulk cancellation
     * — project archival, project deletion, task teardown — can pass between the two. Their own sweeps
     * would eventually catch the job, but only on a later pass, with the queue meanwhile reading
     * {@code CANCELLED} while a container runs.
     */
    private void stopCancelled(UUID pendingId, UUID jobId) {
        LOG.infof("pending job %s was cancelled mid-dispatch; stopping the job %s it started",
                pendingId, jobId);
        try {
            jobService.stopOpen(openJob(jobId), CANCELLED_MID_DISPATCH);
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot stop job %s after its queue entry was cancelled", jobId);
        }
    }

    // Re-read: the worker is assigned by the scheduler in its own transaction, so the entity the
    // launcher handed back may not carry it yet.
    private static List<Job.Open> openJob(UUID jobId) {
        return QuarkusTransaction.requiringNew().call(() -> Job.<Job>findByIdOptional(jobId)
                .filter(job -> !job.isCompleted())
                .map(job -> List.of(Job.Open.of(job)))
                .orElseGet(List::of));
    }
}
