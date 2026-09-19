package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.message.ClientboundMessage;
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
    // todo subject to refactor
    @OnClose
    @Blocking
    public void onClose() {
        var idStr = connection.userData().get(INTERNAL_WORKER_ID);
        if (idStr == null) return;
        workerService.unregisterWorker(UUID.fromString(idStr), connection);
    }

    @OnTextMessage
    @Blocking
    public ClientboundMessage acceptMessage(ServerboundMessage message) {
        if (message instanceof ServerboundMessage.Register register) {
            if (connection.userData().get(INTERNAL_WORKER_ID) == null) {
                return null;
            }
            return handleWorkerRegister(register);
        }
        if (connection.userData().get(INTERNAL_WORKER_ID) == null) {
            return new ClientboundMessage.Response(false, "not registered");
        }
        eventBus.publish(WorkerEvent.SERVERBOUND_EVENT, new WorkerEvent.C2S(workerId(), message));
        return switch (message) {
            case ServerboundMessage.Register r -> handleWorkerRegister(r);
            case ServerboundMessage.UpdateJobLog u -> handleUpdateJobLog(u);
            case ServerboundMessage.UpdateResourceInfo u -> handleUpdateResourceInfo(u);
            case ServerboundMessage.JobCreated created -> handleActionResponse(created);
            case ServerboundMessage.VolumeAck ack -> handleActionResponse(ack);
            case ServerboundMessage.JobStateUpdate u -> handleJobStateUpdate(u);
            case ServerboundMessage.UploadArtifactRequest r -> handleUploadArtifactRequest(r);
            default -> null;
        };
    }

    @OnError
    public ClientboundMessage onError(Throwable error) {
        LOG.errorf(error, "cannot handle a message from worker %s: %v",
                connection.userData().get(INTERNAL_WORKER_ID), error);
        return new ClientboundMessage.Response(false, error.getMessage());
    }

    private ClientboundMessage handleWorkerRegister(ServerboundMessage.Register r) {
        if (connection.userData().get(INTERNAL_WORKER_ID) != null)
            return new ClientboundMessage.Response(false, "already registered on this connection");
        try {
            workerService.registerWorker(
                    r.workerId(), new Worker(r.name(), new WorkerClient(connection), r.info()));
        } catch (IllegalStateException e) {
            // Registration rejected (e.g. duplicate active session); connection remains unregistered.
            return new ClientboundMessage.Response(false, e.getMessage());
        }
        connection.userData().put(INTERNAL_WORKER_ID, r.workerId().toString());
        return new ClientboundMessage.Response(true, "");
    }

    private ClientboundMessage handleUpdateJobLog(ServerboundMessage.UpdateJobLog u) {
        try {
            jobService.appendLog(u.jobId(), u.topic(), u.message(), u.error());
            return new ClientboundMessage.Response(true, "");
        } catch (NoSuchElementException | IllegalStateException e) {
            LOG.errorf("cannot update job log for job %s: %s", u.jobId(), e.getMessage());
            return new ClientboundMessage.Response(false, e.getMessage());
        }
    }

    private ClientboundMessage handleUpdateResourceInfo(ServerboundMessage.UpdateResourceInfo u) {
        workerService.getWorker(workerId()).ifPresent(worker -> worker.setInfo(u.info()));
        return new ClientboundMessage.Response(true, "updated");
    }

    private <T extends ServerboundMessage & ServerboundMessage.ActionResponse>
    ClientboundMessage handleActionResponse(T created) {
        var accepted = workerService.getWorker(workerId())
                .map(worker -> worker.client.getRequest(created.requestId()))
                .map(it -> it.complete(created))
                .orElse(false);
        return new ClientboundMessage.Response(accepted, accepted ? "" : "not registered");
    }

    private ClientboundMessage handleJobStateUpdate(ServerboundMessage.JobStateUpdate u) {
        jobService.applyState(u.jobId(), u.state());
        return new ClientboundMessage.Response(true, "");
    }

    private ClientboundMessage handleUploadArtifactRequest(ServerboundMessage.UploadArtifactRequest r) {
        try {
            return artifactService.begin(workerId(), r.jobId(), r.name(), r.sizeBytes());
        } catch (NoSuchElementException | IllegalStateException | IllegalArgumentException e) {
            LOG.errorf("cannot begin artifact upload for job %s: %s", r.jobId(), e.getMessage());
            return new ClientboundMessage.Response(false, e.getMessage());
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot begin artifact upload for job %s", r.jobId());
            return new ClientboundMessage.Response(false, e.getMessage() == null ? "upload failed" : e.getMessage());
        }
    }

    private UUID workerId() {
        return UUID.fromString(connection.userData().get(INTERNAL_WORKER_ID));
    }
}
