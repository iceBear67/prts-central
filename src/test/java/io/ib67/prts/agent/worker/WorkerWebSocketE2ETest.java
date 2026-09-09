package io.ib67.prts.agent.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobState;
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
 * End-to-end tests for worker WebSocket connections (/ws/worker).
 *
 * <p>Tests handshake authentication, registration, resource reporting, and disconnect handling.
 */
@QuarkusTest
@Tag("e2e")
class WorkerWebSocketE2ETest {

    private static final String SECRET = "test-worker-secret";
    private static final String WORKER_TOKEN = "X-Worker-Token";

    private static final Duration REPLY = Duration.ofSeconds(10);
    private static final Duration SETTLE = Duration.ofSeconds(10);

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
        // Ensure active workers are cleared before the next test runs.
        await(() -> workerService.getActiveWorkers().isEmpty(), "a worker session outlived its test");
    }

    @Test
    void anUncredentialedHandshakeIsRejected() {
        assertEquals(401, rejectedStatus(builder -> builder));
    }

    @Test
    void aWrongSecretIsRejected() {
        assertEquals(401, rejectedStatus(builder -> builder.header(WORKER_TOKEN, "test-worker-secreT")));
    }

    /** Invalid secret lengths are rejected. */
    @Test
    void aSecretOfTheWrongLengthIsRejected() {
        assertEquals(401, rejectedStatus(builder -> builder.header(WORKER_TOKEN, "test-worker")));
    }

    /** Personal access tokens cannot be used to authenticate worker WebSocket connections. */
    @Test
    void aPersonalAccessTokenIsNotAWorkersCredential() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        assertEquals(401, rejectedStatus(builder ->
                builder.header("Authorization", "Bearer " + admin.token())));
    }

    /** Connecting to the WebSocket does not register the worker until a Register message is received. */
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
        var admin = fixtures.createActor("root");
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

    /** Re-registering with a new name updates the worker record. */
    @Test
    void registeringUnderANewNameRenamesTheRow() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var workerId = fixtures.createWorker("old-name");

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

    @Test
    void aRegisteredWorkerReportsItsResources() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(UUID.randomUUID(), "w1", null)).ok());

        assertTrue(session.send(new ServerboundMessage.UpdateResourceInfo(info(3))).ok());

        as(admin).get("/api/worker").then()
                .statusCode(200)
                .body("[0].info.pending", equalTo(3))
                .body("[0].info.current.numCpus", equalTo(2));
    }

    /** Disconnecting a worker marks its open jobs as FAILED. */
    @Test
    void disconnectingFailsOnlyTheJobsThatWorkerWasRunning() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small", null);
        var workerId = UUID.randomUUID();
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        var mine = fixtures.createJob(project, alice, klass, JobState.RUNNING, workerId);
        var elsewhere = fixtures.createJob(project, alice, klass, JobState.RUNNING, fixtures.createWorker("w2"));

        session.close();

        await(() -> stateOf(mine) == JobState.FAILED, "the running job was never failed");
        assertEquals(JobState.RUNNING, stateOf(elsewhere));
    }

    private Session connect() {
        var session = new Session(CLIENT.newWebSocketBuilder().header(WORKER_TOKEN, SECRET));
        sessions.add(session);
        return session;
    }

    private int rejectedStatus(UnaryOperator<WebSocket.Builder> credentials) {
        var builder = credentials.apply(CLIENT.newWebSocketBuilder());
        var failure = assertThrows(CompletionException.class,
                () -> builder.buildAsync(socketUri(), new Inbox()).join());
        if (!(failure.getCause() instanceof WebSocketHandshakeException handshake)) {
            throw new AssertionError("expected a refused handshake", failure);
        }
        return handshake.getResponse().statusCode();
    }

    private URI socketUri() {
        return URI.create("ws://" + baseUri.getAuthority() + "/ws/worker");
    }

    /** Helper for managing test WebSocket sessions. */
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

    /** WebSocket listener that buffers incoming text messages. */
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
