package io.ib67.prts.agent.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.job.JobSpec;
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
    private final ObjectMapper mapper;
    private final Map<UUID, CompletableFuture<UUID>> outstanding = new ConcurrentHashMap<>();

    public WorkerClient(WebSocketConnection conn, ObjectMapper mapper) {
        this.conn = conn;
        this.mapper = mapper;
    }

    public WebSocketConnection conn() {
        return conn;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public Uni<Void> sendMessage(ClientboundMessage message) {
        return conn.sendText(message);
    }

    /**
     * Asks the worker to create a job and blocks until it reports the new {@code jobId}.
     */
    public UUID createJob(JobSpec spec, ResourceClass resourceClass) {
        var requestId = UUID.randomUUID();
        var future = new CompletableFuture<UUID>();
        outstanding.put(requestId, future);
        try {
            conn.sendText(new ClientboundMessage.CreateJob(requestId, spec, resourceClass))
                    .await().atMost(SEND_TIMEOUT);
            return future.get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.completeExceptionally(e);
            throw new IllegalStateException("failed to create job on worker", e);
        } finally {
            outstanding.remove(requestId);
        }
    }

    void completeCreate(UUID requestId, UUID jobId) {
        var future = outstanding.get(requestId);
        if (future != null) {
            future.complete(jobId);
        }
    }

    void failAll(Throwable cause) {
        outstanding.values().forEach(future -> future.completeExceptionally(cause));
        outstanding.clear();
    }
}
