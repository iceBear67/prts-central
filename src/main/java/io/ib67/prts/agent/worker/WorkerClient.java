package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.message.ClientboundEnvelope;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundEnvelope;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.quarkus.arc.ClientProxy;
import io.quarkus.websockets.next.WebSocketConnection;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One worker's side of the wire. Everything it can be asked goes through {@link #call}; the named
 * operations live on {@link WorkerService}.
 */
public final class WorkerClient {
    private static final Logger LOG = Logger.getLogger(WorkerClient.class);

    private final WebSocketConnection conn;
    /** Answers still owed to us, keyed by the envelope id the worker must reply to. */
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
     * Sends one message and completes when the worker answers it.
     *
     * <p>A refusal is a failure of the operation, so an {@code Ack(ok = false)} completes the future
     * exceptionally carrying the worker's own explanation; so do a send that never left, and an
     * answer that does not arrive within {@code timeout}.
     *
     * <p>Never wait on the returned future from a {@code @OnTextMessage} handler of this same
     * connection: the endpoint processes a connection's messages one at a time, so the answer being
     * waited for cannot be read until the handler returns.
     */
    public CompletableFuture<ServerboundMessage> call(ClientboundMessage message, Duration timeout) {
        var envelope = ClientboundEnvelope.of(message);
        var future = new CompletableFuture<ServerboundMessage>();
        outstanding.put(envelope.id(), future);
        conn.sendText(envelope).onFailure().invoke(future::completeExceptionally).subscribe().with(i -> {
        });
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        // Drops the entry however this ends: answered, refused, timed out, or never sent.
        return future.whenComplete((answer, failure) -> outstanding.remove(envelope.id()));
    }

    /**
     * Hands an answer to whoever is waiting for it.
     *
     * <p>Nothing waits for the answer to a message that was sent without joining its future, which
     * is why an unclaimed answer is only logged.
     */
    void complete(ServerboundEnvelope envelope) {
        var waiting = outstanding.remove(envelope.replyTo());
        if (waiting == null) {
            LOG.debugf("worker answered %s, which nobody is waiting for", envelope.replyTo());
            return;
        }
        if (envelope.message() instanceof ServerboundMessage.Ack(var ok, var reason) && !ok) {
            waiting.completeExceptionally(new IllegalStateException(
                    reason.isEmpty() ? "the worker refused the request" : reason));
            return;
        }
        waiting.complete(envelope.message());
    }

    public void failAll(Throwable reason) {
        for (var futures : outstanding.values()) {
            futures.completeExceptionally(reason);
        }
    }

    /**
     * Closes the WebSocket session.
     */
    void close(Duration timeout) {
        conn.close().await().atMost(timeout);
    }
}
