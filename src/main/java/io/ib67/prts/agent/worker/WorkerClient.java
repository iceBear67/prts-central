package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.quarkus.arc.ClientProxy;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class WorkerClient {
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
    private static final long CREATE_TIMEOUT_SECONDS = 30;

    private final WebSocketConnection conn;
    // Pending create-job acknowledgments keyed by request ID.
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

    /**
     * Sends a create job request to the worker and blocks until acknowledged.
     */
    public void createJob(UUID jobId, JobSpec spec, ResourceClass resourceClass) {
        var requestId = UUID.randomUUID();
        var future = new CompletableFuture<Void>();
        outstanding.put(requestId, future);
        try {
            // Secrets are extracted explicitly because JobSpec.secret is excluded from serialization.
            var message = new ClientboundMessage.CreateJob(
                    requestId, jobId, spec, resourceClass, spec.secret());
            conn.sendText(message).await().atMost(SEND_TIMEOUT);
            future.get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw new IllegalStateException("failed to create job on worker", e);
        } finally {
            outstanding.remove(requestId);
        }
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

    void failAll(Throwable cause) {
        outstanding.values().forEach(future -> future.completeExceptionally(cause));
        outstanding.clear();
    }
}
