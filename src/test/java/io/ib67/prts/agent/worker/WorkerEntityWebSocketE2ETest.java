package io.ib67.prts.agent.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.agent.worker.message.ClientboundEnvelope;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundEnvelope;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobLog;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static io.ib67.prts.testing.Fixtures.as;
import static io.ib67.prts.testing.Fixtures.inTx;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end tests for worker WebSocket connections (/ws/worker).
 *
 * <p>Tests handshake authentication, registration, resource reporting, reports on jobs, and
 * disconnect handling.
 */
@QuarkusTest
@Tag("e2e")
class WorkerEntityWebSocketE2ETest {

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
    VolumeService volumeService;
    @Inject
    OpenConnections openConnections;
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
                .body("items.id", contains(workerId.toString()))
                .body("items[0].name", equalTo("w1"))
                .body("items[0].connected", equalTo(true))
                .body("items[0].disabled", equalTo(false));
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

    /**
     * A worker workerId is self-asserted, so taking one over would hand the claimant every job — and every
     * project secret — routed to it. The live session keeps the workerId.
     */
    @Test
    void aSecondRegistrationUnderALiveIdIsRefused() {
        var workerId = UUID.randomUUID();
        var incumbent = connect();
        assertTrue(incumbent.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());

        var response = connect().send(new ServerboundMessage.Register(workerId, "impostor", null));

        assertFalse(response.ok());
        assertTrue(response.message().contains("already has a live session"), response.message());
        assertTrue(incumbent.isOpen());
        assertEquals("w1", workerService.getWorker(workerId).orElseThrow().getName());
    }

    /** The refused connection never registered, so it cannot reach anything else either. */
    @Test
    void aRefusedClaimantStaysUnregistered() {
        var workerId = UUID.randomUUID();
        assertTrue(connect().send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        var claimant = connect();
        claimant.send(new ServerboundMessage.Register(workerId, "impostor", null));

        var response = claimant.send(new ServerboundMessage.UpdateResourceInfo(info(0)));

        assertFalse(response.ok());
        assertEquals("not registered", response.message());
    }

    /** Refusing a takeover must not strand a worker whose previous session went away. */
    @Test
    void reconnectingAfterADisconnectStillRegisters() {
        var workerId = UUID.randomUUID();
        var first = connect();
        assertTrue(first.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        first.close();
        await(() -> workerService.getActiveWorkers().isEmpty(), "the closed session was never dropped");

        assertTrue(connect().send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
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
                .body("items[0].info.pending", equalTo(3))
                .body("items[0].info.current.numCpus", equalTo(2));
    }

    // ---- reporting on jobs ----

    /** Otherwise any worker could end, or write into, a job of any project. */
    @Test
    void aWorkerCannotReportOnAJobPlacedOnAnother() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(UUID.randomUUID(), "w1", null)).ok());
        var job = fixtures.createJob(project, alice, klass, JobState.RUNNING, fixtures.createWorker("w2"));

        var state = session.send(new ServerboundMessage.JobStateUpdate(job, JobState.SUCCESS));
        var log = session.send(new ServerboundMessage.UpdateJobLog(job, "stdout", "forged", false));

        assertFalse(state.ok());
        assertEquals("job not assigned to this worker: " + job, state.message());
        assertFalse(log.ok());
        assertEquals("job not assigned to this worker: " + job, log.message());
        assertEquals(JobState.RUNNING, stateOf(job));
        assertEquals(0L, inTx(() -> JobLog.countByJob(job)));
    }

    @Test
    void aWorkerCannotReportOnAJobNeverOfferedToIt() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(UUID.randomUUID(), "w1", null)).ok());
        var job = fixtures.createJob(project, alice, klass, JobState.PENDING, null);

        var response = session.send(new ServerboundMessage.JobStateUpdate(job, JobState.FAILED));

        assertFalse(response.ok());
        assertEquals(JobState.PENDING, stateOf(job));
    }

    /** Nothing was placed through this process, so the placement is read from the job row. */
    @Test
    void aWorkerReportsOnAJobRecordedAsItsOwn() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var workerId = UUID.randomUUID();
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        var job = fixtures.createJob(project, alice, klass, JobState.RUNNING, workerId);

        var log = session.send(new ServerboundMessage.UpdateJobLog(job, "stdout", "hello", false));
        var state = session.send(new ServerboundMessage.JobStateUpdate(job, JobState.SUCCESS));

        assertTrue(log.ok(), log.message());
        assertTrue(state.ok(), state.message());
        assertEquals(JobState.SUCCESS, stateOf(job));
    }

    /**
     * The claim that sets {@code Job.worker} waits for the worker to accept the job, and the worker
     * may report first: here before it even answers the offer.
     */
    @Test
    void aWorkerReportsOnAJobOfferedToItBeforeThePlacementIsClaimed() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var workerId = UUID.randomUUID();
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        var job = fixtures.createJob(project, alice, klass, JobState.PENDING, null);
        var background = Executors.newSingleThreadExecutor();
        try {
            var placed = CompletableFuture.supplyAsync(
                    () -> workerService.schedule(job, klass, Fixtures.spec("alpine")), background);
            var offer = session.receive();
            assertInstanceOf(ClientboundMessage.CreateJob.class, offer.message());

            var log = session.send(new ServerboundMessage.UpdateJobLog(job, "stdout", "starting", false));
            var running = session.send(new ServerboundMessage.JobStateUpdate(job, JobState.RUNNING));
            session.answer(offer, new ServerboundMessage.Ack(true, ""));

            assertTrue(log.ok(), log.message());
            assertTrue(running.ok(), running.message());
            assertTrue(placed.orTimeout(SETTLE.toSeconds(), TimeUnit.SECONDS).join());
            assertEquals(workerId, inTx(() -> Job.<Job>findById(job).getWorker()));
            // Ended here, so the disconnect after the test has nothing left to fail.
            assertTrue(session.send(new ServerboundMessage.JobStateUpdate(job, JobState.SUCCESS)).ok());
            assertEquals(JobState.SUCCESS, stateOf(job));
        } finally {
            background.shutdown();
        }
    }

    /** Disconnecting a worker marks its open jobs as FAILED. */
    @Test
    void disconnectingFailsOnlyTheJobsThatWorkerWasRunning() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var workerId = UUID.randomUUID();
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        var mine = fixtures.createJob(project, alice, klass, JobState.RUNNING, workerId);
        var elsewhere = fixtures.createJob(project, alice, klass, JobState.RUNNING, fixtures.createWorker("w2"));

        session.close();

        await(() -> stateOf(mine) == JobState.FAILED, "the running job was never failed");
        assertEquals(JobState.RUNNING, stateOf(elsewhere));
    }

    /**
     * A registration can replace a session that closed before its onClose ran; that onClose then
     * finds the new session and leaves it be. The window cannot be hit from outside, so the closed
     * session is registered directly on a connection that never sent Register: its onClose has no
     * worker id to act on, which leaves it in the roster exactly as the race does.
     */
    @Test
    void aClosedSessionReplacedByAReRegistrationHasItsJobsFailed() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var workerId = UUID.randomUUID();
        var opened = openConnections.stream().map(WebSocketConnection::id).collect(Collectors.toSet());
        var first = connect();
        var firstOnServer = new AtomicReference<WebSocketConnection>();
        await(() -> {
            openConnections.stream().filter(c -> !opened.contains(c.id())).findFirst().ifPresent(firstOnServer::set);
            return firstOnServer.get() != null;
        }, "the first connection never opened on the server");
        workerService.registerWorker(workerId, new Worker("w1", new WorkerClient(firstOnServer.get()), null));
        var job = fixtures.createJob(project, alice, klass, JobState.RUNNING, workerId);
        first.close();
        await(() -> !firstOnServer.get().isOpen(), "the first connection never closed on the server");
        assertTrue(workerService.getWorker(workerId).isPresent(), "nothing should have unregistered it");

        var second = connect();
        assertTrue(second.send(new ServerboundMessage.Register(workerId, "w1", null)).ok());

        assertEquals(JobState.FAILED, stateOf(job));
        // The first connection's onClose, arriving after the registration that replaced it.
        workerService.unregisterWorker(workerId, firstOnServer.get());
        assertTrue(second.send(new ServerboundMessage.UpdateResourceInfo(info(1))).ok(),
                "the late onClose unregistered the session that replaced it");
    }

    /** Nothing held a session for this worker, as after a restart of the control plane. */
    @Test
    void aRegistrationFailsWhatItsWorkerLeftOpen() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var workerId = fixtures.createWorker("w1");
        var job = fixtures.createJob(project, alice, klass, JobState.RUNNING, workerId);

        assertTrue(connect().send(new ServerboundMessage.Register(workerId, "w1", null)).ok());

        assertEquals(JobState.FAILED, stateOf(job));
    }

    @Test
    void jobsLeftOpenByAPreviousRunAreFailedAtStartup() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        var klass = fixtures.createResourceClass("small");
        var live = UUID.randomUUID();
        assertTrue(connect().send(new ServerboundMessage.Register(live, "w1", null)).ok());
        var running = fixtures.createJob(project, alice, klass, JobState.RUNNING, live);
        var left = fixtures.createJob(project, alice, klass, JobState.RUNNING, fixtures.createWorker("gone"));

        workerService.failJobsWithoutASession(new StartupEvent());

        assertEquals(JobState.FAILED, stateOf(left));
        assertEquals(JobState.RUNNING, stateOf(running));
    }

    /** The worker may have allocated it and lost only the answer, so the row is kept. */
    @Test
    void aVolumeCreationLeftUnansweredByADisconnectStaysProvisioning() {
        var project = fixtures.createProject("mine");
        var session = connect();
        assertTrue(session.send(new ServerboundMessage.Register(UUID.randomUUID(), "w1", null)).ok());
        var background = Executors.newSingleThreadExecutor();
        try {
            var created = CompletableFuture.supplyAsync(
                    () -> volumeService.create(project, "shared", 1024), background);
            var request = assertInstanceOf(ClientboundMessage.CreateVolume.class, session.receive().message());

            session.close();

            var failure = assertThrows(ExecutionException.class,
                    () -> created.get(SETTLE.toSeconds(), TimeUnit.SECONDS));
            assertEquals("worker disconnected", failure.getCause().getMessage());
            assertEquals(VolumeState.PROVISIONING,
                    inTx(() -> WorkerVolume.<WorkerVolume>findById(request.volumeId()).getState()));
        } finally {
            background.shutdown();
        }
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

        /** Sends one message and takes its answer, which must name what it answers. */
        ClientboundMessage.Ack send(ServerboundMessage message) {
            try {
                var asked = new ServerboundEnvelope(UUID.randomUUID(), null, message);
                socket.sendText(mapper.writeValueAsString(asked), true).join();
                var answer = mapper.readValue(inbox.take(), ClientboundEnvelope.class);
                assertEquals(asked.id(), answer.replyTo(), "the answer names another message");
                return (ClientboundMessage.Ack) answer.message();
            } catch (JsonProcessingException e) {
                throw new AssertionError(e);
            }
        }

        /** Answers a message the control plane asked. */
        void answer(ClientboundEnvelope asked, ServerboundMessage reply) {
            try {
                var answer = new ServerboundEnvelope(UUID.randomUUID(), asked.id(), reply);
                socket.sendText(mapper.writeValueAsString(answer), true).join();
            } catch (JsonProcessingException e) {
                throw new AssertionError(e);
            }
        }

        /** Takes the next message the control plane sent on its own initiative. */
        ClientboundEnvelope receive() {
            try {
                return mapper.readValue(inbox.take(), ClientboundEnvelope.class);
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

    private static Worker.Info info(int pending) {
        return Worker.Info.builder()
                .current(new Worker.Info.Resources(2, 512, 1024))
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
