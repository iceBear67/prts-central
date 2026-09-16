package io.ib67.prts.worker.mock;

import java.net.URI;
import java.net.http.HttpClient;

import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The wire: a plain JDK WebSocket client pointed at the control plane's worker endpoint.
 */
final class WorkerSocket implements Sink {
    /** The header {@code WorkerAuthMechanism} reads. */
    static final String TOKEN_HEADER = "X-Worker-Token";

    private static final String DEFAULT_PATH = "/ws/worker";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final URI uri;
    private final WebSocket socket;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WorkerSocket(URI uri, WebSocket socket) {
        this.uri = uri;
        this.socket = socket;
    }

    /**
     * Opens a worker connection.
     *
     * @param onText   invoked with every complete text message, on the client's own thread
     * @param onGone   invoked once when the connection drops, however it dropped
     * @throws IllegalStateException if the control plane refuses the handshake
     */
    static WorkerSocket open(URI controlPlane, String token, Consumer<String> onText, Runnable onGone) {
        var uri = workerUri(controlPlane);
        var listener = new Listener(onText, onGone);
        try {
            var socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .header(TOKEN_HEADER, token)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(uri, listener)
                    .join();
            return new WorkerSocket(uri, socket);
        } catch (CompletionException e) {
            throw new IllegalStateException(refusal(uri, e), e.getCause());
        }
    }

    /**
     * Turns a base URL into the worker endpoint. A path other than {@code /} is taken as the endpoint
     * itself, so a deployment behind a proxy can be named outright.
     */
    static URI workerUri(URI controlPlane) {
        var scheme = switch (controlPlane.getScheme() == null ? "" : controlPlane.getScheme()) {
            case "http", "ws" -> "ws";
            case "https", "wss" -> "wss";
            default -> throw new IllegalArgumentException(
                    "cannot derive a WebSocket URL from " + controlPlane
                            + "; use http(s):// or ws(s)://");
        };
        if (controlPlane.getAuthority() == null) {
            throw new IllegalArgumentException(controlPlane + " has no host");
        }
        var path = controlPlane.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = DEFAULT_PATH;
        }
        return URI.create(scheme + "://" + controlPlane.getAuthority() + path);
    }

    private static String refusal(URI uri, CompletionException failure) {
        if (failure.getCause() instanceof WebSocketHandshakeException handshake) {
            return "the control plane refused the worker handshake to " + uri
                    + ": HTTP " + handshake.getResponse().statusCode();
        }
        var cause = failure.getCause() == null ? failure : failure.getCause();
        return "cannot open a worker connection to " + uri + ": " + cause;
    }

    @Override
    public void send(String text) {
        if (!isOpen()) {
            throw new IllegalStateException("the connection to " + uri + " is closed");
        }
        socket.sendText(text, true).join();
    }

    @Override
    public boolean isOpen() {
        return !closed.get() && !socket.isInputClosed() && !socket.isOutputClosed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }
    }

    @Override
    public void abort() {
        closed.set(true);
        socket.abort();
    }

    URI uri() {
        return uri;
    }

    /** Reassembles text frames and reports a dropped connection exactly once. */
    private static final class Listener implements WebSocket.Listener {
        private final Consumer<String> onText;
        private final Runnable gone;
        private final AtomicBoolean reported = new AtomicBoolean();
        private final StringBuilder partial = new StringBuilder();

        private Listener(Consumer<String> onText, Runnable onGone) {
            this.onText = onText;
            this.gone = onGone;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                var complete = partial.toString();
                partial.setLength(0);
                onText.accept(complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            notifyGone();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            notifyGone();
        }

        private void notifyGone() {
            if (reported.compareAndSet(false, true)) {
                gone.run();
            }
        }
    }
}
