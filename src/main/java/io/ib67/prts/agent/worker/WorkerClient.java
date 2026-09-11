package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.quarkus.arc.ClientProxy;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class WorkerClient {
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    private static final long CREATE_TIMEOUT_SECONDS = 30;
    // Allocating a disk can take longer than starting a container.
    private static final long VOLUME_TIMEOUT_SECONDS = 60;

    private final WebSocketConnection conn;
    // Pending acknowledgments keyed by request ID: job creation and volume operations share the table.
    private final Map<UUID, CompletableFuture<Void>> outstanding = new ConcurrentHashMap<>();

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

    /** Whether the session behind this client is still usable. */
    boolean isOpen() {
        return conn.isOpen();
    }

    /**
     * Sends a create job request to the worker and blocks until acknowledged.
     */
    public void createJob(UUID jobId, JobSpec spec, ResourceClass resourceClass) {
        var requestId = UUID.randomUUID();
        // Secrets are extracted explicitly because JobSpec.secret is excluded from serialization.
        await(requestId,
                new ClientboundMessage.CreateJob(requestId, jobId, spec, resourceClass, spec.secret()),
                "create job on worker", CREATE_TIMEOUT_SECONDS);
    }

    /**
     * Asks the worker to allocate a volume and blocks until acknowledged.
     */
    public void createVolume(UUID volumeId, UUID projectId, String name, long sizeBytes) {
        var requestId = UUID.randomUUID();
        await(requestId,
                new ClientboundMessage.CreateVolume(requestId, volumeId, projectId, name, sizeBytes),
                "create volume on worker", VOLUME_TIMEOUT_SECONDS);
    }

    /**
     * Asks the worker to discard a volume and blocks until acknowledged.
     */
    public void deleteVolume(UUID volumeId) {
        var requestId = UUID.randomUUID();
        await(requestId, new ClientboundMessage.DeleteVolume(requestId, volumeId),
                "delete volume on worker", VOLUME_TIMEOUT_SECONDS);
    }

    /**
     * Sends a cancellation request to the worker.
     */
    public void cancelJob(UUID jobId) {
        try {
            conn.sendText(new ClientboundMessage.CancelJob(jobId)).await().atMost(SEND_TIMEOUT);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to cancel job on worker", e);
        }
    }

    /**
     * Instructs the worker to terminate and discard the job immediately.
     */
    public void interruptJob(UUID jobId, String reason) {
        try {
            conn.sendText(new ClientboundMessage.InterruptJob(jobId, reason))
                    .await().atMost(SEND_TIMEOUT);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to interrupt job on worker", e);
        }
    }

    /** Closes the WebSocket session. */
    void close() {
        conn.close().await().atMost(SEND_TIMEOUT);
    }

    void completeCreate(UUID requestId) {
        var future = outstanding.get(requestId);
        if (future != null) {
            future.complete(null);
        }
    }

    /** Settles a volume request, carrying the worker's refusal reason back to the caller. */
    void completeVolume(UUID requestId, boolean ok, @Nullable String message) {
        var future = outstanding.get(requestId);
        if (future == null) {
            return;
        }
        if (ok) {
            future.complete(null);
        } else {
            future.completeExceptionally(new IllegalStateException(
                    message == null || message.isBlank() ? "the worker refused" : message));
        }
    }

    void failAll(Throwable cause) {
        outstanding.values().forEach(future -> future.completeExceptionally(cause));
        outstanding.clear();
    }

    /**
     * Sends a request and blocks until the worker acknowledges it.
     */
    private void await(UUID requestId, ClientboundMessage message, String what, long timeoutSeconds) {
        var future = new CompletableFuture<Void>();
        outstanding.put(requestId, future);
        try {
            conn.sendText(message).await().atMost(SEND_TIMEOUT);
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // The worker answered with a refusal; its reason is the useful part.
            var cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("failed to " + what + ": " + cause.getMessage(), cause);
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw new IllegalStateException("failed to " + what, e);
        } finally {
            outstanding.remove(requestId);
        }
    }
}
