package io.ib67.prts.agent.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobState;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import static io.ib67.prts.testing.Fixtures.as;
import static io.ib67.prts.testing.Fixtures.inTx;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The worker socket: who gets through the handshake, and what the protocol answers once they are in.
 *
 * <p>{@link WorkerWebSocket} carries no security annotation, so the enforcement is invisible from the
 * class: {@code quarkus.http.auth.permission.worker_ws} pins {@code /ws/worker} to the
 * {@code worker-token} mechanism alone, so nothing but the shared secret is even consulted. That is
 * the claim the rejection tests below are here to keep true.
 *
 * <p>Not covered: re-registering the same worker on a second connection. The interesting half is that
 * closing the *displaced* connection must not unregister the live one, and a negative like that cannot
 * be observed from the client — the server's close handling is asynchronous and offers no handle to
 * wait on. Likewise the {@code @OnError} reply to an undecodable message, whose routing through
 * websockets-next could not be established from the sources.
 */
@QuarkusTest
@Tag("e2e")
class WorkerWebSocketE2ETest {

    /** {@code worker.secret} under {@code %test} in application.yml. */
    private static final String SECRET = "test-worker-secret";
    private static final String WORKER_TOKEN = "X-Worker-Token";

    private static final Duration REPLY = Duration.ofSeconds(10);
    /** For what the server does after a close, which no client-side future covers. */
    private static final Duration SETTLE = Duration.ofSeconds(10);

    // Its threads are daemons, so there is nothing to close: the test JVM is free to exit over them.
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @TestHTTPResource
    URI baseUri;

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;
    @Inject
    WorkerService workerService;
    @Inject
    ObjectMapper mapper;

    private final List<Session> sessions = new ArrayList<>();

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
    }

    @AfterEach
    void disconnect() {
        sessions.forEach(Session::closeQuietly);
        // The roster is a field on an @ApplicationScoped bean and so outlives the test class. A session
        // left registered would still count as schedulable in whatever runs next.
        await(() -> workerService.getActiveWorkers().isEmpty(), "a worker session outlived its test");
    }

    // ---- the handshake ----

    @Test
    void anUncredentialedHandshakeIsRejected() {
        assertEquals(401, rejectedStatus(builder -> builder));
    }

    @Test
    void aWrongSecretIsRejected() {
        assertEquals(401, rejectedStatus(builder -> builder.header(WORKER_TOKEN, "test-worker-secreT")));
    }

    /** The mechanism compares lengths before contents; both refusals must look the same. */
    @Test
    void aSecretOfTheWrongLengthIsRejected() {
        assertEquals(401, rejectedStatus(builder -> builder.header(WORKER_TOKEN, "test-worker")));
    }

    /** {@code worker_ws} names one mechanism, so the token that opens every /api route is not read here. */
    @Test
    void aPersonalAccessTokenIsNotAWorkersCredential() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);

        assertEquals(401, rejectedStatus(builder ->
                builder.header("Authorization", "Bearer " + admin.token())));
    }

    /** Opening the socket is not registering: only a {@code register} message sets the worker id. */
    @Test
    void theSharedSecretOpensTheSocketWithoutRegisteringAnything() {
        var session = connect();

        assertTrue(session.isOpen());
        assertTrue(workerService.getActiveWorkers().isEmpty());
    }

    // ---- registering ----

    @Test
    void aMessageBeforeRegisteringIsRefused() {
        var session = connect();

        var response = session.send(new ServerboundMessage.UpdateResourceInfo(info(0)));

        assertFalse(response.ok());
        assertEquals("not registered", response.message());
    }

    @Test
    void registeringAnnouncesTheWorker() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        var workerId = UUID.randomUUID();

        var response = connect().send(new ServerboundMessage.Register(workerId, "w1", null));

        assertTrue(response.ok());
        assertEquals("", response.message());
        as(admin).get("/api/worker").then()
                .statusCode(200)
                .body("id", contains(workerId.toString()))
                .body("[0].name", equalTo("w1"))
                .body("[0].connected", equalTo(true))
                .body("[0].disabled", equalTo(false));
    }

    /** {@code Worker.upsert} keeps the row across reconnects and takes the name it comes back under. */
    @Test
    void registeringUnderANewNameRenamesTheRow() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        var workerId = fixtures.worker("old-name");

        assertTrue(connect().send(new ServerboundMessage.Register(workerId, "new-name", null)).ok());

        as(admin).get("/api/worker/{id}", workerId).then()
                .statusCode(200)
                .body("name", equalTo("new-name"));
    }

    @Test
    void registeringTwiceOnOneConnectionIsRefused() {
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(UUID.randomUUID(), "w1", null)).ok());

        var response = session.send(new ServerboundMessage.Register(UUID.randomUUID(), "w2", null));

        assertFalse(response.ok());
        assertEquals("already registered on this connection", response.message());
    }

    // ---- what a registered worker may say ----

    @Test
    void aRegisteredWorkerReportsItsResources() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(UUID.randomUUID(), "w1", null)).ok());

        assertTrue(session.send(new ServerboundMessage.UpdateResourceInfo(info(3))).ok());

        as(admin).get("/api/worker").then()
                .statusCode(200)
                .body("[0].info.pending", equalTo(3))
                .body("[0].info.current.numCpus", equalTo(2));
    }

    /** Nobody is left to finish them, so a disconnect is what closes out a worker's open jobs. */
    @Test
    void disconnectingFailsOnlyTheJobsThatWorkerWasRunning() {
        var alice = fixtures.actor("alice");
        var project = fixtures.project("mine");
        var klass = fixtures.resourceClass("small", null);
        var workerId = UUID.randomUUID();
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        var mine = fixtures.job(project, alice, klass, JobState.RUNNING, workerId);
        var elsewhere = fixtures.job(project, alice, klass, JobState.RUNNING, fixtures.worker("w2"));

        session.close();

        await(() -> stateOf(mine) == JobState.FAILED, "the running job was never failed");
        // listOpenByWorker selects its set up front and this one was not in it, so a job already failed
        // means the pass that could have touched the other is over.
        assertEquals(JobState.RUNNING, stateOf(elsewhere));
    }

    // ---- the client side ----

    private Session connect() {
        var session = new Session(CLIENT.newWebSocketBuilder().header(WORKER_TOKEN, SECRET));
        sessions.add(session);
        return session;
    }

    /** The handshake status a refused connection came back with. */
    private int rejectedStatus(UnaryOperator<WebSocket.Builder> credentials) {
        var builder = credentials.apply(CLIENT.newWebSocketBuilder());
        var failure = assertThrows(CompletionException.class,
                () -> builder.buildAsync(socketUri(), new Inbox()).join());
        if (!(failure.getCause() instanceof WebSocketHandshakeException handshake)) {
            throw new AssertionError("expected a refused handshake", failure);
        }
        return handshake.getResponse().statusCode();
    }

    /** The path is fixed by {@link WorkerWebSocket}; only the port the test server took has to be read off. */
    private URI socketUri() {
        return URI.create("ws://" + baseUri.getAuthority() + "/ws/worker");
    }

    /** A worker's end of the socket: one request, one reply, in the order they were sent. */
    private final class Session {
        private final WebSocket socket;
        private final Inbox inbox = new Inbox();

        private Session(WebSocket.Builder builder) {
            this.socket = builder.buildAsync(socketUri(), inbox).join();
        }

        ClientboundMessage.Response send(ServerboundMessage message) {
            try {
                socket.sendText(mapper.writerFor(ServerboundMessage.class).writeValueAsString(message), true)
                        .join();
                return (ClientboundMessage.Response)
                        mapper.readValue(inbox.take(), ClientboundMessage.class);
            } catch (JsonProcessingException e) {
                throw new AssertionError(e);
            }
        }

        boolean isOpen() {
            return !socket.isInputClosed() && !socket.isOutputClosed();
        }

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }

        void closeQuietly() {
            try {
                close();
            } catch (RuntimeException ignored) {
                socket.abort();
            }
        }
    }

    /** Reassembles text frames and holds the messages until a test asks for one. */
    private static final class Inbox implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            // The default onOpen requests one message; each one after it has to be asked for.
            socket.request(1);
            return null;
        }

        String take() {
            String message;
            try {
                message = messages.poll(REPLY.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertNotNull(message, "no reply within " + REPLY);
            return message;
        }
    }

    // ---- reading the outcome ----

    private static JobState stateOf(UUID jobId) {
        return inTx(() -> Job.<Job>findById(jobId).getState());
    }

    private static RegisteredWorker.Info info(int pending) {
        return RegisteredWorker.Info.builder()
                .current(new RegisteredWorker.Info.Resources(2, 512, 1024))
                .pending(pending)
                .build();
    }

    private static void await(BooleanSupplier settled, String message) {
        var deadline = System.nanoTime() + SETTLE.toNanos();
        while (!settled.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(message + ", after " + SETTLE);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(message);
            }
        }
    }
}
