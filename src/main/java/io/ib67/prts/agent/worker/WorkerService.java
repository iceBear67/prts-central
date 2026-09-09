package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.entity.JobState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
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
    // Synchronizes registration and state toggling so worker state remains consistent.
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

    /** Registers an active worker session, replacing any previous session with the same ID. */
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
     * Enables or disables a worker for job scheduling.
     */
    public Worker setDisabled(UUID id, boolean disabled) {
        synchronized (roster) {
            var row = QuarkusTransaction.requiringNew().call(() -> {
                var worker = requireRow(id);
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

    /**
     * Renames a registered worker.
     *
     * <p>Note that if the worker reconnects with a different name in its registration,
     * {@link Worker#upsert} will overwrite this value.
     */
    public Worker rename(UUID id, String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var worker = requireRow(id);
            worker.setName(name);
            return worker;
        });
    }

    /**
     * Closes an active worker session, if connected. Any unfinished jobs on the worker will fail.
     */
    public void disconnect(UUID id) {
        var worker = activeWorkers.get(id);
        if (worker != null) {
            worker.getRpc().close();
        }
    }

    /**
     * Deletes a worker's registration.
     *
     * <p>The worker must be disconnected, have no in-flight jobs, and host no volumes before deletion.
     */
    public void delete(UUID id) {
        synchronized (roster) {
            if (activeWorkers.containsKey(id)) {
                throw new ClientErrorException(
                        "worker " + id + " is connected; disconnect it first", Response.Status.CONFLICT);
            }
            QuarkusTransaction.requiringNew().run(() -> {
                var worker = requireRow(id);
                var open = Job.listOpenByWorker(id).size();
                if (open > 0) {
                    throw new ClientErrorException(
                            "worker " + id + " still has " + open + " unfinished job(s)",
                            Response.Status.CONFLICT);
                }
                var volumes = WorkerVolume.countByWorker(id);
                if (volumes > 0) {
                    throw new ClientErrorException(
                            "worker " + id + " still hosts " + volumes + " volume(s)",
                            Response.Status.CONFLICT);
                }
                worker.delete();
            });
        }
    }

    private static Worker requireRow(UUID id) {
        var worker = Worker.<Worker>findById(id);
        if (worker == null) {
            throw new NoSuchElementException("no such worker: " + id);
        }
        return worker;
    }

    /** Unregisters a worker if the closing connection matches its active session. */
    void unregisterWorker(UUID id, WebSocketConnection connection) {
        var worker = activeWorkers.get(id);
        if (worker == null || !worker.getRpc().isFor(connection) || !activeWorkers.remove(id, worker)) {
            return;
        }
        scheduler.onWorkerRemoved(id);
        worker.getRpc().failAll(new IllegalStateException("worker disconnected"));
        failJobsOf(id);
    }

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
     * Attempts to place a job on an eligible worker.
     *
     * @return true if successfully placed, false otherwise
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
     * Asks the worker running the job to cancel it.
     *
     * @return true if the message was sent, false if the worker is disconnected
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
     * Tells the worker to terminate and discard the job.
     *
     * @return true if the message was sent, false if the worker is disconnected
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
