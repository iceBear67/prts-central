package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.Perm;
import io.ib67.prts.agent.acp.entity.AgentSession;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.message.ClientboundMessage;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.ProjectRole;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static io.ib67.prts.testing.Fixtures.as;
import static io.ib67.prts.testing.Fixtures.inTx;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end tests for the ACP proxy: a mock worker on {@code /ws/worker}, a browser on
 * {@code /ws/project/.../agent}, and the transcript both of them leave behind.
 */
@QuarkusTest
@Tag("e2e")
class AgentWebSocketE2ETest {

    private static final String WORKER_TOKEN = "X-Worker-Token";
    private static final String SECRET = "test-worker-secret";
    private static final String ROOT = "sess-root";

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
    JobService jobService;
    @Inject
    ObjectMapper mapper;

    private final List<WebSocket> sockets = new ArrayList<>();

    private Fixtures.Actor owner;
    private UUID projectId;
    private UUID workerId;
    private UUID jobId;
    private WorkerSession worker;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        owner = fixtures.createActor("owner");
        projectId = fixtures.createProject("agents", owner);
        var klass = fixtures.createResourceClass("small");
        workerId = UUID.randomUUID();
        worker = new WorkerSession();
        assertTrue(worker.call(new ServerboundMessage.Register(workerId, "w1", null)).ok());
        jobId = fixtures.createJob(projectId, owner, klass, JobState.RUNNING, workerId);
    }

    @AfterEach
    void disconnect() {
        sockets.forEach(socket -> {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            } catch (RuntimeException ignored) {
                socket.abort();
            }
        });
        await(() -> workerService.getActiveWorkers().isEmpty(), "a worker session outlived its test");
    }

    // ---- attaching ----

    @Test
    void aJobWithoutAnAgentHasNoSocketToOffer() {
        assertEquals(AgentService.NO_AGENT, new Viewer(owner).closeCode());
    }

    @Test
    void aNonMemberIsTurnedAwayWithoutLearningWhetherTheJobExists() {
        attachAgent();
        var stranger = fixtures.createActor("stranger");

        assertEquals(AgentService.FORBIDDEN, new Viewer(stranger).closeCode());
    }

    @Test
    void aWorkerCannotAnnounceAnAgentForAJobItDoesNotHost() {
        var elsewhere = fixtures.createJob(
                projectId, owner, fixtures.createResourceClass("other"), JobState.RUNNING,
                fixtures.createWorker("w2"));

        var response = worker.call(new ServerboundMessage.AgentAttached(
                elsewhere, mapper.createObjectNode(), ROOT));

        assertFalse(response.ok());
        assertTrue(response.message().contains("is not on worker"), response.message());
    }

    // ---- the conversation ----

    @Test
    void initializeIsAnsweredFromWhatTheWorkerReported() {
        attachAgent();
        var viewer = new Viewer(owner);

        viewer.send("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}""");

        var result = viewer.take().get("result");
        assertEquals(1, result.get("protocolVersion").asInt());
        var meta = result.get("_meta").get("prts");
        assertEquals(ROOT, meta.get("rootSessionId").asText());
        assertEquals(jobId.toString(), meta.get("jobId").asText());
    }

    @Test
    void aPromptCrossesToTheWorkerAndItsAnswerFindsItsWayBack() {
        attachAgent();
        var viewer = new Viewer(owner);

        viewer.send("""
                {"jsonrpc":"2.0","id":7,"method":"session/prompt",
                 "params":{"sessionId":"%s","prompt":[{"type":"text","text":"hi"}]}}""".formatted(ROOT));

        var forwarded = worker.takeAgentFrame();
        assertEquals(jobId, forwarded.jobId());
        assertEquals("session/prompt", forwarded.frame().get("method").asText());
        worker.push(new ServerboundMessage.AgentFrame(jobId, read("""
                {"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}"""
                .formatted(forwarded.frame().get("id").asLong()))));

        var answer = viewer.take();
        assertEquals(7, answer.get("id").asInt(), "the viewer's own id did not come back");
        assertEquals("end_turn", answer.get("result").get("stopReason").asText());
    }

    @Test
    void anUpdateFromTheAgentReachesTheViewer() {
        attachAgent();
        var viewer = new Viewer(owner);

        worker.push(new ServerboundMessage.AgentFrame(jobId, read("""
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s",
                 "update":{"sessionUpdate":"agent_message_chunk",
                 "content":{"type":"text","text":"working"}}}}""".formatted(ROOT))));

        var update = viewer.take();
        assertEquals("session/update", update.get("method").asText());
        assertEquals("working",
                update.get("params").get("update").get("content").get("text").asText());
    }

    @Test
    void aMethodTheProxyDoesNotCarryIsRefused() {
        attachAgent();
        var viewer = new Viewer(owner);

        viewer.send("""
                {"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/tmp"}}""");

        assertEquals(AcpFrame.METHOD_NOT_FOUND, viewer.take().get("error").get("code").asInt());
    }

    /** A VIEWER holds job:agent:read by role but not job:agent:interact, which needs MEMBER. */
    @Test
    void aProjectViewerMayWatchButNotPrompt() {
        attachAgent();
        var watcher = fixtures.createActor("watcher");
        fixtures.join(watcher, projectId, ProjectRole.VIEWER);
        var viewer = new Viewer(watcher);

        viewer.send("""
                {"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"%s"}}"""
                .formatted(ROOT));

        var error = viewer.take().get("error");
        assertEquals(AcpFrame.FORBIDDEN, error.get("code").asInt());
        assertTrue(error.get("message").asText().contains(Perm.JOB_AGENT_INTERACT.permission()));
    }

    // ---- more than one session ----

    @Test
    void aSessionTheAgentOpensLaterIsRecordedUnderTheRoot() {
        attachAgent();
        var viewer = new Viewer(owner);

        worker.push(new ServerboundMessage.AgentFrame(jobId, read("""
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sub-1","update":{}}}""")));
        assertEquals("sub-1", viewer.take().get("params").get("sessionId").asText());

        var sessions = as(owner).get(agentPath() + "/session").then()
                .statusCode(200)
                .body("size()", equalTo(2))
                .body("[0].sessionId", equalTo(ROOT))
                .body("[0].parent", nullValue())
                .body("[1].sessionId", equalTo("sub-1"))
                .extract().jsonPath();
        assertEquals(sessions.getString("[0].id"), sessions.getString("[1].parent"));
    }

    // ---- what it leaves behind ----

    @Test
    void theTranscriptKeepsBothDirectionsInTheOrderTheyCrossed() {
        attachAgent();
        var viewer = new Viewer(owner);

        viewer.send("""
                {"jsonrpc":"2.0","id":7,"method":"session/prompt","params":{"sessionId":"%s"}}"""
                .formatted(ROOT));
        var forwarded = worker.takeAgentFrame();
        worker.push(new ServerboundMessage.AgentFrame(jobId, read("""
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{}}}"""
                .formatted(ROOT))));
        viewer.take();

        var root = inTx(() -> AgentSession.listByJob(jobId).getFirst().getId());
        as(owner).get(agentPath() + "/session/{sessionId}/event", root).then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items[0].direction", equalTo("FROM_CLIENT"))
                .body("items[0].method", equalTo("session/prompt"))
                .body("items[0].actor.id", equalTo(owner.id().toString()))
                // Recorded as the agent's end of the wire saw it, under central's id.
                .body("items[0].frame.id", equalTo((int) forwarded.frame().get("id").asLong()))
                .body("items[1].direction", equalTo("FROM_AGENT"))
                .body("items[1].method", equalTo("session/update"))
                .body("items[1].actor", nullValue());
    }

    @Test
    void aFinishedJobClosesItsViewersAndItsSessions() {
        attachAgent();
        var viewer = new Viewer(owner);

        jobService.applyState(jobId, JobState.SUCCESS);

        assertEquals(WebSocket.NORMAL_CLOSURE, viewer.closeCode());
        await(() -> inTx(() -> AgentSession.listByJob(jobId).getFirst().getClosedAt()) != null,
                "the session was never closed");
    }

    @Test
    void aDisconnectedWorkerTakesItsViewersWithIt() {
        attachAgent();
        var viewer = new Viewer(owner);

        worker.close();

        assertEquals(WebSocket.NORMAL_CLOSURE, viewer.closeCode());
    }

    // ---- helpers ----

    private void attachAgent() {
        var response = worker.call(new ServerboundMessage.AgentAttached(
                jobId, mapper.createObjectNode().put("protocolVersion", 1), ROOT));
        assertTrue(response.ok(), response.message());
    }

    private String agentPath() {
        return "/api/project/" + projectId + "/job/" + jobId + "/agent";
    }

    private URI workerUri() {
        return URI.create("ws://" + baseUri.getAuthority() + "/ws/worker");
    }

    private URI agentUri() {
        return URI.create("ws://" + baseUri.getAuthority()
                + "/ws/project/" + projectId + "/job/" + jobId + "/agent");
    }

    /** A browser watching the job's agent. */
    private final class Viewer {
        private final Inbox inbox = new Inbox();
        private final WebSocket socket;

        private Viewer(Fixtures.Actor actor) {
            socket = CLIENT.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + actor.token())
                    .buildAsync(agentUri(), inbox)
                    .join();
            sockets.add(socket);
        }

        private void send(String frame) {
            socket.sendText(frame, true).join();
        }

        private JsonNode take() {
            return read(inbox.take());
        }

        private int closeCode() {
            try {
                return inbox.closed.get(REPLY.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("the socket was never closed", e);
            }
        }
    }

    /**
     * A mock worker. Acknowledgments and agent frames share the connection, so the two are split as
     * they arrive rather than assuming the next message answers the last send.
     */
    private final class WorkerSession {
        private final BlockingQueue<ClientboundMessage.Response> responses = new LinkedBlockingQueue<>();
        private final BlockingQueue<ClientboundMessage.AgentFrame> frames = new LinkedBlockingQueue<>();
        private final WebSocket socket;

        private WorkerSession() {
            socket = CLIENT.newWebSocketBuilder()
                    .header(WORKER_TOKEN, SECRET)
                    .buildAsync(workerUri(), new Inbox(this::accept))
                    .join();
            sockets.add(socket);
        }

        private void accept(String text) {
            try {
                switch (mapper.readValue(text, ClientboundMessage.class)) {
                    case ClientboundMessage.Response response -> responses.add(response);
                    case ClientboundMessage.AgentFrame frame -> frames.add(frame);
                    default -> fail("the worker was sent something this test does not expect: " + text);
                }
            } catch (Exception e) {
                throw new AssertionError(text, e);
            }
        }

        private ClientboundMessage.Response call(ServerboundMessage message) {
            try {
                socket.sendText(
                        mapper.writerFor(ServerboundMessage.class).writeValueAsString(message), true)
                        .join();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return poll(responses, "no acknowledgment");
        }

        /** Sends a message whose acknowledgment the test does not care about. */
        private void push(ServerboundMessage message) {
            assertTrue(call(message).ok());
        }

        private ClientboundMessage.AgentFrame takeAgentFrame() {
            return poll(frames, "no agent frame reached the worker");
        }

        private void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }
    }

    /** Buffers inbound text, and remembers the close code. */
    private static final class Inbox implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        private final Consumer<String> sink;

        private Inbox() {
            this(null);
        }

        private Inbox(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                var message = partial.toString();
                partial.setLength(0);
                if (sink == null) {
                    messages.add(message);
                } else {
                    sink.accept(message);
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        private String take() {
            return poll(messages, "no frame reached the viewer");
        }
    }

    private static <T> T poll(BlockingQueue<T> queue, String message) {
        T taken;
        try {
            taken = queue.poll(REPLY.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertNotNull(taken, message + ", after " + REPLY);
        return taken;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(json, e);
        }
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
