package io.ib67.prts.agent.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.quarkus.arc.ClientProxy;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkerClient {
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final WebSocketConnection conn;
    // Pending acknowledgments keyed by request ID: job creation and volume operations share the table.
    private final Map<UUID, CompletableFuture<ServerboundMessage>> outstanding = new ConcurrentHashMap<>();

    // Unwraps the client proxy so messages can be sent outside the WebSocket request context.
    public WorkerClient(WebSocketConnection conn) {
        this.conn = ClientProxy.unwrap(conn);
    }

    /**
     * Checks if this client wraps the given WebSocket connection.
     */
    boolean isFor(WebSocketConnection connection) {
        return conn.id().equals(connection.id());
    }

    /**
     * Returns true if the underlying WebSocket connection is open.
     */
    boolean isOpen() {
        return conn.isOpen();
    }

    /**
     * Sends a create job request to the worker and blocks until acknowledged.
     */
    public CompletableFuture<ServerboundMessage.JobCreated> createJob(UUID jobId, JobSpec spec, ResourceClass resourceClass) {
        var requestId = UUID.randomUUID();
        // Secrets are extracted explicitly because JobSpec.secret is excluded from serialization.
        return uniWithTrack(requestId, new ClientboundMessage.CreateJob(requestId, jobId, spec, resourceClass, spec.secret()))
                .thenApply(it -> (ServerboundMessage.JobCreated) it);
    }

    /**
     * Asks the worker to allocate a volume and blocks until acknowledged.
     *
     * @return
     */
    public CompletableFuture<ServerboundMessage.VolumeAck> createVolume(UUID volumeId, UUID projectId, String name, long sizeBytes) {
        var requestId = UUID.randomUUID();
        return uniWithTrack(requestId, new ClientboundMessage.CreateVolume(requestId, volumeId, projectId, name, sizeBytes))
                .thenApply(it -> (ServerboundMessage.VolumeAck) it);
    }

    /**
     * Asks the worker to discard a volume and blocks until acknowledged.
     *
     * @return
     */
    public CompletableFuture<ServerboundMessage.VolumeAck> deleteVolume(UUID volumeId) {
        var requestId = UUID.randomUUID();
        return uniWithTrack(requestId, new ClientboundMessage.DeleteVolume(requestId, volumeId))
                .thenApply(it -> (ServerboundMessage.VolumeAck) it);
    }

    /**
     * Sends a cancellation request to the worker.
     *
     * @return
     */
    public Uni<Void> cancelJob(UUID jobId) {
        return conn.sendText(new ClientboundMessage.CancelJob(jobId));
    }

    /**
     * Dispatches an unacknowledged ACP frame to the job's agent.
     *
     * @return
     */
    public Uni<Void> sendAgentFrame(UUID jobId, JsonNode frame) {
        return conn.sendText(new ClientboundMessage.AgentFrame(jobId, frame));
    }

    public void sendMessage(ClientboundMessage message) {
        conn.sendText(message).subscribe().with(t -> {});
    }

    /**
     * Instructs the worker to terminate and discard the job immediately.
     */
    public Uni<Void> interruptJob(UUID jobId, String reason) {
        return conn.sendText(new ClientboundMessage.InterruptJob(jobId, reason));
    }

    public void failAll(Throwable reason) {
        for (var futures : outstanding.values()) {
            futures.completeExceptionally(reason);
        }
    }

    /**
     * Closes the WebSocket session.
     */
    void close() {
        conn.close().await().atMost(SEND_TIMEOUT);
    }

    CompletableFuture<ServerboundMessage> getRequest(UUID requestId) {
        return outstanding.get(requestId);
    }

    /**
     * Sends a request and blocks until the worker acknowledges it.
     */
    private CompletableFuture<ServerboundMessage> uniWithTrack(UUID requestId, ClientboundMessage message) {
        var future = new CompletableFuture<ServerboundMessage>();
        outstanding.put(requestId, future);
        conn.sendText(message).onFailure().invoke(future::completeExceptionally).subscribe().with(i -> {
        });
        return future;
    }
}
