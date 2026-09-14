package io.ib67.prts.agent.acp;

import io.quarkus.arc.ClientProxy;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.UUID;

/**
 * Attached WebSocket viewer for an ACP agent.
 *
 * @param mayInteract whether the viewer holds {@code job:agent:interact} to prompt, cancel, or answer
 */
record AgentViewer(WebSocketConnection connection, UUID userId, boolean mayInteract) {

    private static final Logger LOG = Logger.getLogger(AgentViewer.class);

    AgentViewer {
        // Unwrap proxy to allow dispatching from worker threads outside the connection's CDI context.
        connection = ClientProxy.unwrap(Objects.requireNonNull(connection, "connection"));
        Objects.requireNonNull(userId, "userId");
    }

    String id() {
        return connection.id();
    }

    void send(AcpFrame frame) {
        sendTo(connection, frame);
    }

    /**
     * Asynchronously sends a frame to the viewer. Write failures are logged without blocking upstream relays.
     */
    static void sendTo(WebSocketConnection connection, AcpFrame frame) {
        connection.sendText(frame.toString()).subscribe().with(
                sent -> {
                },
                failure -> LOG.debugf(failure, "cannot reach viewer %s", connection.id()));
    }

    void close(CloseReason reason) {
        connection.close(reason).subscribe().with(
                closed -> {
                },
                failure -> LOG.debugf(failure, "cannot close viewer %s", id()));
    }
}
