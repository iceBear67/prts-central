package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.message.ClientboundEnvelope;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundEnvelope;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.ib67.prts.storage.ArtifactService;
import io.ib67.prts.job.JobService;
import io.quarkus.websockets.next.*;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.eventbus.EventBus;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.NoSuchElementException;
import java.util.UUID;

// Authentication is configured via quarkus.http.auth.permission.worker_ws (WorkerAuthMechanism).
@WebSocket(path = "/ws/worker")
public class WorkerWebSocket {
    private static final Logger LOG = Logger.getLogger(WorkerWebSocket.class);
    private static final UserData.TypedKey<String> INTERNAL_WORKER_ID = UserData.TypedKey.forString("worker_id");
    @Inject
    WebSocketConnection connection;
    @Inject
    WorkerService workerService;
    @Inject
    JobService jobService;
    @Inject
    ArtifactService artifactService;

    @Inject
    EventBus eventBus;

    // Disconnecting unregisters the worker and fails any orphaned running jobs.
    @OnClose
    @Blocking
    public void onClose() {
        var idStr = connection.userData().get(INTERNAL_WORKER_ID);
        if (idStr == null) return;
        workerService.unregisterWorker(UUID.fromString(idStr), connection);
    }

    /**
     * Answers every message the worker asks, and nothing else.
     *
     * <p>Nothing here may wait for this worker's answer to something: the endpoint reads a
     * connection's next message only once this returns, so such a wait can never be satisfied.
     */
    @OnTextMessage
    @Blocking
    public ClientboundEnvelope acceptMessage(ServerboundEnvelope envelope) {
        if (envelope.replyTo() != null) {
            if (connection.userData().get(INTERNAL_WORKER_ID) != null) {
                workerService.getWorker(workerId()).ifPresent(worker -> worker.getClient().complete(envelope));
            }
            return null;
        }
        try {
            return envelope.reply(handle(envelope.message()));
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot handle %s from worker %s",
                    envelope.message().getClass().getSimpleName(),
                    connection.userData().get(INTERNAL_WORKER_ID));
            return envelope.reply(new ClientboundMessage.Ack(false, String.valueOf(e.getMessage())));
        }
    }

    // Reached only when the envelope itself could not be decoded, which leaves no id to answer.
    @OnError
    public ClientboundEnvelope onError(Throwable error) {
        LOG.errorf(error, "cannot decode a message from worker %s",
                connection.userData().get(INTERNAL_WORKER_ID));
        return ClientboundEnvelope.of(new ClientboundMessage.Ack(false, String.valueOf(error.getMessage())));
    }

    private ClientboundMessage handle(ServerboundMessage message) {
        if (message instanceof ServerboundMessage.Register register) {
            return handleWorkerRegister(register);
        }
        if (connection.userData().get(INTERNAL_WORKER_ID) == null) {
            return new ClientboundMessage.Ack(false, "not registered");
        }
        eventBus.publish(WorkerEvent.SERVERBOUND_EVENT, new WorkerEvent.C2S(workerId(), message));
        return switch (message) {
            // actually unreachable
            case ServerboundMessage.Register r -> handleWorkerRegister(r);
            case ServerboundMessage.UpdateJobLog u -> handleUpdateJobLog(u);
            case ServerboundMessage.UpdateResourceInfo u -> handleUpdateResourceInfo(u);
            case ServerboundMessage.JobStateUpdate u -> handleJobStateUpdate(u);
            case ServerboundMessage.UploadArtifactRequest r -> handleUploadArtifactRequest(r);
            // The agent messages are served by AgentService off the event bus above; this says they
            // were received and dispatched.
            case ServerboundMessage.AgentAttached ignored -> new ClientboundMessage.Ack(true, "");
            case ServerboundMessage.AgentFrame ignored -> new ClientboundMessage.Ack(true, "");
            case ServerboundMessage.AgentDetached ignored -> new ClientboundMessage.Ack(true, "");
            case ServerboundMessage.Ack ignored ->
                    new ClientboundMessage.Ack(false, "an acknowledgment must name what it answers");
            case ServerboundMessage.Unknown ignored ->
                    new ClientboundMessage.Ack(false, "unknown message type");
        };
    }

    private ClientboundMessage handleWorkerRegister(ServerboundMessage.Register r) {
        if (connection.userData().get(INTERNAL_WORKER_ID) != null)
            return new ClientboundMessage.Ack(false, "already registered on this connection");
        try {
            workerService.registerWorker(
                    r.workerId(), new Worker(r.name(), new WorkerClient(connection), r.info()));
        } catch (IllegalStateException e) {
            // Registration rejected (e.g. duplicate active session); connection remains unregistered.
            return new ClientboundMessage.Ack(false, e.getMessage());
        }
        connection.userData().put(INTERNAL_WORKER_ID, r.workerId().toString());
        return new ClientboundMessage.Ack(true, "");
    }

    private ClientboundMessage handleUpdateJobLog(ServerboundMessage.UpdateJobLog u) {
        if (!workerService.isPlacedOn(u.jobId(), workerId())) {
            return refuseNotPlaced(u.jobId());
        }
        try {
            jobService.appendLog(u.jobId(), u.topic(), u.message(), u.error());
            return new ClientboundMessage.Ack(true, "");
        } catch (NoSuchElementException | IllegalStateException e) {
            LOG.errorf("cannot update job log for job %s: %s", u.jobId(), e.getMessage());
            return new ClientboundMessage.Ack(false, e.getMessage());
        }
    }

    private ClientboundMessage handleUpdateResourceInfo(ServerboundMessage.UpdateResourceInfo u) {
        workerService.getWorker(workerId()).ifPresent(worker -> worker.setInfo(u.info()));
        return new ClientboundMessage.Ack(true, "updated");
    }

    private ClientboundMessage handleJobStateUpdate(ServerboundMessage.JobStateUpdate u) {
        if (!workerService.isPlacedOn(u.jobId(), workerId())) {
            return refuseNotPlaced(u.jobId());
        }
        jobService.applyState(u.jobId(), u.state());
        return new ClientboundMessage.Ack(true, "");
    }

    // Any registered worker could otherwise end, or write into, a job of any project.
    private ClientboundMessage refuseNotPlaced(UUID jobId) {
        LOG.warnf("worker %s reported on job %s, which was not placed on it", workerId(), jobId);
        return new ClientboundMessage.Ack(false, "job not assigned to this worker: " + jobId);
    }

    private ClientboundMessage handleUploadArtifactRequest(ServerboundMessage.UploadArtifactRequest r) {
        try {
            return artifactService.begin(workerId(), r.jobId(), r.name(), r.sizeBytes());
        } catch (NoSuchElementException | IllegalStateException | IllegalArgumentException e) {
            LOG.errorf("cannot begin artifact upload for job %s: %s", r.jobId(), e.getMessage());
            return new ClientboundMessage.Ack(false, e.getMessage());
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot begin artifact upload for job %s", r.jobId());
            return new ClientboundMessage.Ack(false, e.getMessage() == null ? "upload failed" : e.getMessage());
        }
    }

    private UUID workerId() {
        return UUID.fromString(connection.userData().get(INTERNAL_WORKER_ID));
    }
}
