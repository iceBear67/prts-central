package io.ib67.prts.worker.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for object storage: it accepts whatever is PUT to it and remembers the bytes.
 */
final class LocalStorage implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger status = new AtomicInteger(204);
    private final List<Stored> stored = new CopyOnWriteArrayList<>();

    LocalStorage() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** Makes every later request fail with this status. */
    void refuse(int status) {
        this.status.set(status);
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    List<Stored> stored() {
        return List.copyOf(stored);
    }

    private void handle(HttpExchange exchange) throws IOException {
        stored.add(new Stored(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestBody().readAllBytes()));
        exchange.sendResponseHeaders(status.get(), -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record Stored(String method, String path, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
