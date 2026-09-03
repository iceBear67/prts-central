package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
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
    /**
     * Acks we are still waiting for, keyed by the id of the <em>attempt</em>. Not by job id: after a
     * timeout the same job can be offered again, and a late ack for the abandoned attempt must not
     * be mistaken for an ack of the new one.
     */
    private final Map<UUID, CompletableFuture<Void>> outstanding = new ConcurrentHashMap<>();

    public WorkerClient(WebSocketConnection conn) {
        this.conn = conn;
    }

    /** Whether this handle speaks over {@code connection} — how a closing socket proves it owns a session. */
    boolean isFor(WebSocketConnection connection) {
        return conn.equals(connection);
    }

    /**
     * Asks the worker to run {@code jobId} and blocks until it acknowledges.
     */
    public void createJob(UUID jobId, JobSpec spec, ResourceClass resourceClass) {
        var requestId = UUID.randomUUID();
        var future = new CompletableFuture<Void>();
        outstanding.put(requestId, future);
        try {
            // Lifted out of the spec by hand: JobSpec#secret() is @JsonIgnore'd, so the spec on the
            // wire carries none and this field is the only copy the worker gets.
            var secrets = spec == null ? null : spec.secret();
            conn.sendText(new ClientboundMessage.CreateJob(requestId, jobId, spec, resourceClass, secrets))
                    .await().atMost(SEND_TIMEOUT);
            future.get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw new IllegalStateException("failed to create job on worker", e);
        } finally {
            outstanding.remove(requestId);
        }
    }

    /**
     * Tells the worker to stop {@code jobId}. Not acknowledged: the job is already terminal on our
     * side, so there is nothing to wait for.
     */
    public void cancelJob(UUID jobId) {
        try {
            conn.sendText(new ClientboundMessage.CancelJob(jobId)).await().atMost(SEND_TIMEOUT);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to cancel job on worker", e);
        }
    }

    /** A late ack for an attempt we already gave up on finds nothing here, which is intended. */
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
