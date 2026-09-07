package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.entity.JobState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class WorkerService {
    private static final Logger LOG = Logger.getLogger(WorkerService.class);

    @Inject
    JobService jobService;

    private final Map<UUID, RegisteredWorker> activeWorkers = new ConcurrentHashMap<>();
    private final WorkerScheduler scheduler = new WorkerScheduler(activeWorkers);
    /**
     * Orders a register against a {@link #setDisabled}: the row is read and then mirrored onto the
     * session in two steps, and a flag written between them would reach the old session or none.
     */
    private final Object roster = new Object();

    public Map<UUID, RegisteredWorker> getActiveWorkers() {
        return Collections.unmodifiableMap(activeWorkers);
    }

    public boolean hasSchedulableWorker() {
        return activeWorkers.values().stream().anyMatch(worker -> !worker.isDisabled());
    }

    public Optional<RegisteredWorker> getWorker(UUID id) {
        return Optional.ofNullable(activeWorkers.get(id));
    }

    /** Newest connection wins: a half-open socket must not lock out the reconnect that replaces it. */
    void registerWorker(UUID id, RegisteredWorker registeredWorker) {
        RegisteredWorker displaced;
        synchronized (roster) {
            registeredWorker.setDisabled(QuarkusTransaction.requiringNew()
                    .call(() -> Worker.upsert(id, registeredWorker.getName()).isDisabled()));
            displaced = activeWorkers.put(id, registeredWorker);
        }
        if (displaced != null) {
            scheduler.onWorkerRemoved(id);
            displaced.getRpc().failAll(new IllegalStateException("worker re-registered on a new connection"));
        }
    }

    /**
     * Takes the worker out of scheduling, or back in; what it is already running is left alone.
     * Throws {@link NoSuchElementException} for a worker that never registered.
     */
    public Worker setDisabled(UUID id, boolean disabled) {
        synchronized (roster) {
            var row = QuarkusTransaction.requiringNew().call(() -> {
                var worker = Worker.<Worker>findById(id);
                if (worker == null) {
                    throw new NoSuchElementException("no such worker: " + id);
                }
                worker.setDisabled(disabled);
                return worker;
            });
            var live = activeWorkers.get(id);
            if (live != null) {
                live.setDisabled(disabled);
            }
            return row;
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
     * Places {@code jobId} on a live worker. {@code false} means it cannot be placed right now — no
     * eligible worker, or its {@link JobLock} is held — and nothing is queued, so it is on the
     * caller to dispose of the job and answer for it. Which of the two reasons it was only reaches
     * the log: it is not a distinction the requester can act on.
     */
    public boolean schedule(UUID jobId, ResourceClass resourceClass, JobSpec spec) {
        var required = requireResourceClass(resourceClass);
        var refused = scheduler.schedule0(jobId, required, spec);
        if (refused == null) {
            return true;
        }
        LOG.infof("job %s was not placed: %s", jobId, refused);
        return false;
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

    /**
     * Tells the worker running {@code jobId} that the job has ceased to exist here — see
     * {@link io.ib67.prts.agent.worker.message.ClientboundMessage.InterruptJob}. {@code false} means
     * that worker is not connected, so there was nobody to tell.
     */
    public boolean interrupt(UUID workerId, UUID jobId, String reason) {
        var worker = activeWorkers.get(workerId);
        if (worker == null) {
            return false;
        }
        worker.getRpc().interruptJob(jobId, reason);
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
}
