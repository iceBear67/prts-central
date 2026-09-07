package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.ib67.prts.storage.ArtifactService;
import io.ib67.prts.project.JobService;
import io.quarkus.websockets.next.*;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.NoSuchElementException;
import java.util.UUID;

@WebSocket(path = "/ws/worker") //todo custom auth
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

    /** Blocking: unregistering fails the jobs this worker owed us an outcome for, which touches the DB. */
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
        if (!(message instanceof ServerboundMessage.Register)
                && connection.userData().get(INTERNAL_WORKER_ID) == null) {
            return new ClientboundMessage.Response(false, "not registered");
        }
        return switch (message) {
            case ServerboundMessage.Register r -> handleWorkerRegister(r);
            case ServerboundMessage.UpdateJobLog u -> handleUpdateJobLog(u);
            case ServerboundMessage.UpdateResourceInfo u -> handleUpdateResourceInfo(u);
            case ServerboundMessage.JobCreated created -> handleJobCreated(created);
            case ServerboundMessage.JobStateUpdate u -> handleJobStateUpdate(u);
            case ServerboundMessage.UploadArtifactRequest r -> handleUploadArtifactRequest(r);
        };
    }

    /**
     * A message whose required fields are missing fails in the decoder, before any handler runs —
     * without this the connection would simply be closed, and a worker still sending {@code workerId}
     * as {@code "id"} would never learn why. Also catches whatever escapes a handler.
     */
    @OnError
    public ClientboundMessage onError(Throwable error) {
        LOG.errorf(error, "cannot handle a message from worker %s",
                connection.userData().get(INTERNAL_WORKER_ID));
        var cause = rootCause(error);
        var message = cause instanceof NullPointerException
                ? "missing field: " + cause.getMessage()
                : cause.getMessage();
        return new ClientboundMessage.Response(
                false, message == null ? cause.getClass().getSimpleName() : message);
    }

    private static Throwable rootCause(Throwable error) {
        var cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private ClientboundMessage handleWorkerRegister(ServerboundMessage.Register r) {
        if (connection.userData().get(INTERNAL_WORKER_ID) != null)
            return new ClientboundMessage.Response(false, "already registered on this connection");
        workerService.registerWorker(
                r.workerId(), new RegisteredWorker(r.name(), new WorkerClient(connection), r.info()));
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
        var updated = workerService.updateInfo(workerId(), u.info());
        return new ClientboundMessage.Response(updated, updated ? "" : "not registered");
    }

    private ClientboundMessage handleJobCreated(ServerboundMessage.JobCreated created) {
        var accepted = workerService.onJobCreated(workerId(), created.requestId());
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
