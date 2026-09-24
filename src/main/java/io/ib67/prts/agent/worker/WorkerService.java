package io.ib67.prts.agent.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.WorkerEntity;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.dto.WorkerRemovalView;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.entity.JobState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.eventbus.EventBus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class WorkerService {
    private static final Logger LOG = Logger.getLogger(WorkerService.class);

    @Inject
    JobService jobService;
    @Inject
    EventBus eventBus;
    @Inject
    WorkerConfig workerConfig;

    private final Map<UUID, Worker> activeWorkers = new ConcurrentHashMap<>();
    /**
     * The worker each job was offered to, the only one that may report on it. Filled from
     * {@link WorkerEvent#ASSIGNED} before the job is sent, since the worker may report as soon as it
     * has the job, before the placement is claimed on {@code Job.worker}; a job missing here is
     * looked up in that column.
     */
    private LoadingCache<UUID, UUID> placements;
    WorkerScheduler scheduler;

    @PostConstruct
    private void postConstruct() {
        scheduler = new WorkerScheduler(activeWorkers, eventBus, this);
        placements = Caffeine.newBuilder()
                .maximumSize(workerConfig.placementCacheSize())
                .build(jobId -> QuarkusTransaction.requiringNew().call(() -> Job.<Job>findByIdOptional(jobId)
                        .map(Job::getWorker)
                        .orElse(null)));
    }

    // Blocking: a put waits for a load of the same key to finish, and a load reads the database.
    @ConsumeEvent(value = WorkerEvent.ASSIGNED, blocking = true)
    void onAssigned(WorkerEvent.Assignment assignment) {
        placements.put(assignment.job(), assignment.worker());
    }

    /**
     * Sends a job to a worker. The future completes once the worker accepts it.
     */
    public CompletableFuture<Void> createJob(UUID workerId, UUID jobId, JobSpec spec, ResourceClass resourceClass) {
        // Secrets are extracted explicitly because JobSpec.secret is excluded from serialization.
        return call(workerId, new ClientboundMessage.CreateJob(jobId, spec, resourceClass, spec.secret()),
                workerConfig.timeout().createJob());
    }

    /**
     * Asks a worker to stop a running job.
     */
    public CompletableFuture<Void> cancelJob(UUID workerId, UUID jobId) {
        return call(workerId, new ClientboundMessage.CancelJob(jobId), workerConfig.timeout().cancelJob());
    }

    /**
     * Instructs a worker to terminate and discard a job immediately.
     */
    public CompletableFuture<Void> interruptJob(UUID workerId, UUID jobId, String reason) {
        return call(workerId, new ClientboundMessage.InterruptJob(jobId, reason),
                workerConfig.timeout().interruptJob());
    }

    /**
     * Asks a worker to allocate a volume.
     */
    public CompletableFuture<Void> createVolume(
            UUID workerId, UUID volumeId, UUID projectId, String name, long sizeBytes) {
        return call(workerId, new ClientboundMessage.CreateVolume(volumeId, projectId, name, sizeBytes),
                workerConfig.timeout().volume());
    }

    /**
     * Asks a worker to discard a volume and its data.
     */
    public CompletableFuture<Void> deleteVolume(UUID workerId, UUID volumeId) {
        return call(workerId, new ClientboundMessage.DeleteVolume(volumeId), workerConfig.timeout().volume());
    }

    /**
     * Dispatches one ACP frame to a job's agent.
     */
    public CompletableFuture<Void> sendAgentFrame(UUID workerId, UUID jobId, JsonNode frame) {
        return call(workerId, new ClientboundMessage.AgentFrame(jobId, frame), workerConfig.timeout().agentFrame());
    }

    /**
     * Sends one message to a worker. The future completes when the worker acknowledges it, and
     * completes exceptionally if the worker refuses it or does not answer within {@code timeout}.
     *
     * @throws NoSuchElementException if the worker holds no live session
     */
    private CompletableFuture<Void> call(UUID workerId, ClientboundMessage message, Duration timeout) {
        return getWorker(workerId)
                .orElseThrow(() -> new NoSuchElementException("worker " + workerId + " is not connected"))
                .getClient().call(message, timeout)
                .thenAccept(answer -> {
                });
    }

    public Map<UUID, Worker> getActiveWorkers() {
        return Collections.unmodifiableMap(activeWorkers);
    }

    public boolean hasSchedulableWorker() {
        return activeWorkers.values().stream().anyMatch(worker -> !worker.isDisabled());
    }

    public Optional<Worker> getWorker(UUID id) {
        return Optional.ofNullable(activeWorkers.get(id));
    }

    /**
     * Registers an active worker session.
     *
     * <p>A worker workerId is whatever the registration claims it is, so taking over a live one would hand the
     * claimant every job — and every project secret — routed to it. A session already closed but not yet
     * unregistered is replaced, so a reconnect after a drop still lands, after every job still open on
     * the worker is failed.
     *
     * @throws IllegalStateException if the worker already holds a live session
     * @throws RuntimeException      if the jobs left open cannot be failed; the worker retries
     */
    void registerWorker(UUID id, Worker worker) {
        synchronized (this) {
            var current = activeWorkers.get(id);
            if (current != null && current.getClient().isOpen()) {
                LOG.warnf("refused a registration for worker %s: its session is still live", id);
                throw new IllegalStateException("worker " + id + " already has a live session");
            }
            if (current != null) {
                // Its own onClose will find this session in its place and return. Removed before the
                // jobs are looked up, as unregisterWorker does, so WorkerScheduler can tell a
                // placement recorded after the lookup. No OFFLINE: its consumer closes agent channels
                // by worker id at no fixed time and could reach the new session's; the old channels
                // close as their jobs fail.
                activeWorkers.remove(id, current);
                scheduler.onWorkerRemoved(id);
                current.getClient().failAll(new IllegalStateException("worker re-registered on a new connection"));
            }
            // No session of this worker is open now, and Job.worker names the worker, not the session:
            // what is still open on it was left by one that ended — replaced above, lost to a restart of
            // this process, or not failed by an unregisterWorker that errored. Failed before the new
            // session enters the roster, where it could be given jobs the lookup would also find.
            failOpenJobs(id);
            worker.setDisabled(QuarkusTransaction.requiringNew()
                    .call(() -> WorkerEntity.upsert(id, worker.getName()).isDisabled()));
            activeWorkers.put(id, worker);
        }
    }

    /**
     * Enables or disables a worker for job scheduling.
     */
    public WorkerEntity setDisabled(UUID id, boolean disabled) {
        synchronized (this) {
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
     * {@link WorkerEntity#upsert} will overwrite this value.
     */
    public WorkerEntity rename(UUID id, String name) {
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
            worker.getClient().close(workerConfig.timeout().close());
        }
    }

    public WorkerRemovalView delete(UUID id, boolean force) {
        synchronized (this) {
            if (activeWorkers.containsKey(id)) {
                throw new ClientErrorException(
                        "worker " + id + " is connected; disconnect it first", Response.Status.CONFLICT);
            }
            if (!force) {
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
                return new WorkerRemovalView(0, 0);
            }
            QuarkusTransaction.requiringNew().run(() -> requireRow(id));
            var failed = failAllJobs(id);
            return QuarkusTransaction.requiringNew().call(() -> {
                var worker = requireRow(id);
                // Volumes first: worker_volume carries a plain foreign key to the row being removed.
                // Their task mounts follow through task_volume's ON DELETE CASCADE.
                var dropped = WorkerVolume.deleteByWorker(id);
                worker.delete();
                return new WorkerRemovalView(dropped, failed);
            });
        }
    }

    private static WorkerEntity requireRow(UUID id) {
        var worker = WorkerEntity.<WorkerEntity>findById(id);
        if (worker == null) {
            throw new NoSuchElementException("no such worker: " + id);
        }
        return worker;
    }

    /**
     * Unregisters a worker if the closing connection matches its active session.
     *
     * <p>Under the same lock as {@link #registerWorker}: a reconnect registering between the removal
     * and the lookup would have the jobs it is given failed as this session's.
     */
    void unregisterWorker(UUID id, WebSocketConnection connection) {
        synchronized (this) {
            var worker = activeWorkers.get(id);
            if (worker == null || !worker.getClient().isFor(connection) || !activeWorkers.remove(id, worker)) {
                return;
            }
            eventBus.publish(WorkerEvent.OFFLINE, id);
            worker.getClient().failAll(new IllegalStateException("worker disconnected"));
            failAllJobs(id);
        }
    }

    /**
     * Fails the jobs of every worker a previous run of this process held a session with. Nothing
     * outlives the process, and each worker stopped its jobs when its connection dropped.
     *
     * <p>A worker already registered again is skipped: its registration failed what it had left, and
     * what it holds now is live. Under the registration lock, so that holds whenever this runs.
     */
    void failJobsWithoutASession(@Observes StartupEvent event) {
        synchronized (this) {
            var lost = QuarkusTransaction.requiringNew().call(() -> Job.listOpenAssigned().stream()
                    .filter(job -> !activeWorkers.containsKey(job.getWorker()))
                    .map(Job::getId)
                    .toList());
            for (var jobId : lost) {
                jobService.applyState(jobId, JobState.FAILED);
            }
            if (!lost.isEmpty()) {
                LOG.infof("failed %s job(s) left open on workers by a previous run", lost.size());
            }
        }
    }

    /** Fails everything the worker was still holding. Returns how many, best effort. */
    private int failAllJobs(UUID workerId) {
        try {
            return failOpenJobs(workerId);
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot fail the jobs of disconnected worker %s", workerId);
            return 0;
        }
    }

    /** Fails every job still open on the worker. Returns how many. */
    private int failOpenJobs(UUID workerId) {
        var open = QuarkusTransaction.requiringNew()
                .call(() -> Job.listOpenByWorker(workerId).stream().map(Job::getId).toList());
        for (var jobId : open) {
            jobService.applyState(jobId, JobState.FAILED);
        }
        return open.size();
    }

    /**
     * Attempts to place a job on an eligible worker.
     *
     * @param resourceClass the class {@code JobLauncher.resolve} already resolved and checked against
     *                      the project. It is all scalars, so the detached instance is everything
     *                      placement needs: re-reading the row would cost a transaction and check less.
     * @return true if successfully placed, false otherwise
     */
    public boolean schedule(UUID jobId, ResourceClass resourceClass, JobSpec spec) {
        if (resourceClass == null || resourceClass.getName() == null) {
            throw new IllegalArgumentException("resource class name is required");
        }
        var refused = scheduler.schedule0(jobId, resourceClass, spec);
        if (refused == null) {
            return true;
        }
        LOG.infof("job %s was not placed: %s", jobId, refused);
        return false;
    }

    /** Selects a connected worker to host a new volume, empty if none is available. */
    Optional<UUID> selectVolumeHost() {
        return scheduler.selectVolumeHost();
    }

    /** Whether the job was placed on the worker, which only then may report on it. */
    boolean isPlacedOn(UUID jobId, UUID workerId) {
        return workerId.equals(placements.get(jobId));
    }
}
