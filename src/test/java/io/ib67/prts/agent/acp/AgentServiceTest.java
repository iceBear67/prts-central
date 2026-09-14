package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.acp.entity.AgentDirection;
import io.ib67.prts.agent.worker.WorkerService;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing, refusing and id rewriting, with the stored side and the worker link mocked out.
 *
 * <p>{@link AgentTranscript} owns every database call the proxy makes, which is what lets the whole of
 * {@link AgentService} be exercised here rather than only against containers.
 */
class AgentServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID WORKER = UUID.randomUUID();
    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ROOT_ROW = UUID.randomUUID();
    private static final UUID SUB_ROW = UUID.randomUUID();
    private static final String ROOT = "sess-root";

    private AgentService service;
    private AgentTranscript transcript;
    private WorkerService workerService;

    @BeforeEach
    void attachAnAgent() {
        transcript = mock(AgentTranscript.class);
        workerService = mock(WorkerService.class);
        var config = mock(AcpConfig.class);
        when(config.maxSessionsPerJob()).thenReturn(2);
        when(config.maxViewersPerJob()).thenReturn(2);

        service = new AgentService();
        service.transcript = transcript;
        service.workerService = workerService;
        service.acpConfig = config;

        when(transcript.openRoot(WORKER, JOB, ROOT))
                .thenReturn(new AgentTranscript.Attachment(PROJECT, ROOT_ROW));
        when(transcript.sessionsOf(JOB)).thenReturn(Map.of());
        service.onAttached(WORKER, JOB, MAPPER.createObjectNode().put("protocolVersion", 1), ROOT);
    }

    // ---- a viewer driving the agent ----

    @Test
    void aPromptReachesTheAgentUnderAnIdOfCentralsOwn() {
        var viewer = new Viewer("v1", true);

        send(viewer, prompt(7, ROOT));

        var forwarded = lastToAgent();
        assertEquals("session/prompt", forwarded.get("method").asText());
        assertNotEquals(7, forwarded.get("id").asInt(), "the viewer's id went out as-is");
        verify(transcript).record(
                eq(ROOT_ROW), eq(AgentDirection.FROM_CLIENT), eq("session/prompt"), any(UUID.class), any());
    }

    @Test
    void theAgentsAnswerComesBackUnderTheViewersOwnId() {
        var viewer = new Viewer("v1", true);
        send(viewer, prompt(7, ROOT));

        fromAgent("""
                {"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}"""
                .formatted(lastToAgent().get("id").asLong()));

        assertEquals(7, viewer.last().get("id").asInt());
        assertEquals("end_turn", viewer.last().get("result").get("stopReason").asText());
    }

    /** Two browsers number their requests independently, and neither may see the other's answer. */
    @Test
    void twoViewersUsingTheSameIdEachGetTheirOwn() {
        var alice = new Viewer("v1", true);
        var bob = new Viewer("v2", true);
        send(alice, prompt(7, ROOT));
        send(bob, prompt(7, ROOT));
        var ids = allToAgent().stream().map(frame -> frame.get("id").asLong()).toList();

        fromAgent("""
                {"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}""".formatted(ids.get(1)));

        assertEquals(1, bob.received.size());
        assertEquals(7, bob.last().get("id").asInt());
        assertTrue(alice.received.isEmpty(), "alice was handed an answer to bob's request");
    }

    @Test
    void aViewerMayNotSpeakIntoASessionTheAgentNeverOpened() {
        var viewer = new Viewer("v1", true);

        send(viewer, prompt(7, "made-up"));

        assertEquals(AcpFrame.INVALID_PARAMS, viewer.last().get("error").get("code").asInt());
        verify(workerService, never()).sendAgentFrame(any(), any(), any());
    }

    /** A viewer forging an update would write into the transcript as though the agent had said it. */
    @Test
    void aViewerMayNotSendWhatOnlyTheAgentSends() {
        var viewer = new Viewer("v1", true);

        send(viewer, """
                {"jsonrpc":"2.0","id":9,"method":"session/update","params":{"sessionId":"%s"}}"""
                .formatted(ROOT));

        assertEquals(AcpFrame.METHOD_NOT_FOUND, viewer.last().get("error").get("code").asInt());
    }

    @Test
    void aViewerWithoutTheInteractPermissionMayNotPrompt() {
        var viewer = new Viewer("v1", false);

        send(viewer, prompt(7, ROOT));

        assertEquals(AcpFrame.FORBIDDEN, viewer.last().get("error").get("code").asInt());
        verify(workerService, never()).sendAgentFrame(any(), any(), any());
    }

    @Test
    void initializeIsAnsweredFromTheSnapshotTheWorkerReported() {
        var viewer = new Viewer("v1", true);

        send(viewer, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}""");

        var result = viewer.last().get("result");
        assertEquals(1, result.get("protocolVersion").asInt());
        var meta = result.get("_meta").get("prts");
        assertEquals(ROOT, meta.get("rootSessionId").asText());
        assertEquals(JOB.toString(), meta.get("jobId").asText());
        assertEquals(ROOT, meta.get("sessions").get(0).get("sessionId").asText());
        assertTrue(meta.get("sessions").get(0).get("root").asBoolean());
        verify(workerService, never()).sendAgentFrame(any(), any(), any());
    }

    // ---- the agent asking a human ----

    @Test
    void anUpdateReachesEveryViewer() {
        var alice = new Viewer("v1", true);
        var bob = new Viewer("v2", false);

        fromAgent("""
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"%s","update":{}}}"""
                .formatted(ROOT));

        assertEquals("session/update", alice.last().get("method").asText());
        assertEquals("session/update", bob.last().get("method").asText());
        verify(transcript).record(
                eq(ROOT_ROW), eq(AgentDirection.FROM_AGENT), eq("session/update"), isNull(), any());
    }

    @Test
    void theFirstViewerToAnswerTheAgentWins() {
        var alice = new Viewer("v1", true);
        var bob = new Viewer("v2", true);
        askPermission("w1");
        var asked = alice.last().get("id").asLong();

        send(alice, permission(asked, "allow"));
        send(bob, permission(asked, "reject"));

        var answers = allToAgent();
        assertEquals(1, answers.size(), "the agent was answered twice");
        assertEquals("w1", answers.getFirst().get("id").asText());
        assertEquals("allow", answers.getFirst().get("result").get("outcome").asText());
    }

    @Test
    void aViewerWithoutTheInteractPermissionCannotAnswerAndLeavesItForSomeoneElse() {
        var watcher = new Viewer("v1", false);
        var operator = new Viewer("v2", true);
        askPermission("w1");
        var asked = operator.last().get("id").asLong();

        send(watcher, permission(asked, "reject"));
        send(operator, permission(asked, "allow"));

        assertEquals(AcpFrame.FORBIDDEN, watcher.last().get("error").get("code").asInt());
        var answers = allToAgent();
        assertEquals(1, answers.size());
        assertEquals("allow", answers.getFirst().get("result").get("outcome").asText());
    }

    /** Central will not guess a human's answer, so it says there is no human instead. */
    @Test
    void theAgentIsToldWhenNobodyIsWatching() {
        askPermission("w1");

        var answer = lastToAgent();
        assertEquals("w1", answer.get("id").asText());
        assertEquals(AcpFrame.INTERNAL_ERROR, answer.get("error").get("code").asInt());
    }

    @Test
    void theLastViewerLeavingReleasesWhatTheAgentIsWaitingOn() {
        var viewer = new Viewer("v1", true);
        askPermission("w1");

        service.detach(JOB, viewer.connection);

        var answer = lastToAgent();
        assertEquals("w1", answer.get("id").asText());
        assertEquals(AcpFrame.INTERNAL_ERROR, answer.get("error").get("code").asInt());
    }

    @Test
    void theAgentMayNotSendWhatOnlyAViewerSends() {
        new Viewer("v1", true);

        fromAgent("""
                {"jsonrpc":"2.0","id":"w2","method":"session/prompt","params":{"sessionId":"%s"}}"""
                .formatted(ROOT));

        assertEquals(AcpFrame.METHOD_NOT_FOUND, lastToAgent().get("error").get("code").asInt());
    }

    /**
     * The container is the worker's, so it serves these itself. Answering rather than dropping keeps
     * the agent from waiting on a browser that was never going to reply.
     */
    @Test
    void aFilesystemRequestIsRefusedRatherThanIgnored() {
        var viewer = new Viewer("v1", true);

        fromAgent("""
                {"jsonrpc":"2.0","id":"w3","method":"fs/read_text_file",
                 "params":{"sessionId":"%s","path":"/etc/passwd"}}""".formatted(ROOT));

        var answer = lastToAgent();
        assertEquals("w3", answer.get("id").asText());
        assertEquals(AcpFrame.METHOD_NOT_FOUND, answer.get("error").get("code").asInt());
        assertTrue(viewer.received.isEmpty(), "the browser was asked to read a file");
    }

    // ---- more than one session ----

    @Test
    void aSessionCentralHasNotSeenIsRecordedAsASubagents() {
        when(transcript.openChild(ROOT_ROW, "sub-1")).thenReturn(SUB_ROW);
        var viewer = new Viewer("v1", true);

        fromAgent("""
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sub-1","update":{}}}""");

        verify(transcript).openChild(ROOT_ROW, "sub-1");
        verify(transcript).record(
                eq(SUB_ROW), eq(AgentDirection.FROM_AGENT), eq("session/update"), isNull(), any());
        assertEquals("sub-1", viewer.last().get("params").get("sessionId").asText());
    }

    @Test
    void aJobWillNotOpenMoreSessionsThanItsLimit() {
        when(transcript.openChild(ROOT_ROW, "sub-1")).thenReturn(SUB_ROW);
        fromAgent("""
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sub-1","update":{}}}""");

        // Root plus sub-1 is the configured limit of two.
        fromAgent("""
                {"jsonrpc":"2.0","id":"w4","method":"session/request_permission",
                 "params":{"sessionId":"sub-2"}}""");

        verify(transcript, never()).openChild(ROOT_ROW, "sub-2");
        assertEquals(AcpFrame.INVALID_PARAMS, lastToAgent().get("error").get("code").asInt());
    }

    // ---- the channel's life ----

    @Test
    void aWorkerThatDoesNotHostTheJobIsRefused() {
        var stranger = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> service.onFrame(stranger, JOB, read(prompt(1, ROOT))));
    }

    @Test
    void aFinishedJobClosesItsSessionsAndItsViewers() {
        var viewer = new Viewer("v1", true);

        service.onJobClosed(JOB);

        verify(viewer.connection).close(any(CloseReason.class));
        verify(transcript).closeAll(JOB);
        assertThrows(IllegalStateException.class,
                () -> service.onFrame(WORKER, JOB, read(prompt(1, ROOT))));
    }

    @Test
    void aDisconnectedWorkerTakesItsChannelsWithIt() {
        new Viewer("v1", true);

        service.onWorkerGone(WORKER);

        assertThrows(IllegalStateException.class,
                () -> service.onFrame(WORKER, JOB, read(prompt(1, ROOT))));
    }

    @Test
    void oneViewerTooManyIsRefused() {
        new Viewer("v1", true);
        new Viewer("v2", true);

        assertThrows(IllegalStateException.class, () -> new Viewer("v3", true));
    }

    /** The record is what a conversation is reviewed from, so nothing may act ahead of it. */
    @Test
    void aFrameIsRecordedBeforeItIsForwarded() {
        var viewer = new Viewer("v1", true);

        send(viewer, prompt(7, ROOT));

        var order = inOrder(transcript, workerService);
        order.verify(transcript).record(eq(ROOT_ROW), any(), eq("session/prompt"), any(UUID.class), any());
        order.verify(workerService).sendAgentFrame(eq(WORKER), eq(JOB), any());
    }

    // ---- helpers ----

    /** A browser watching the job, already attached. */
    private final class Viewer {
        private final WebSocketConnection connection = mock(WebSocketConnection.class);
        private final List<JsonNode> received = new ArrayList<>();

        private Viewer(String id, boolean mayInteract) {
            when(connection.id()).thenReturn(id);
            when(connection.sendText(anyString())).thenAnswer(call -> {
                received.add(read(call.getArgument(0)));
                return Uni.createFrom().voidItem();
            });
            when(connection.close(any(CloseReason.class))).thenReturn(Uni.createFrom().voidItem());
            service.attach(PROJECT, JOB, connection, UUID.randomUUID(), mayInteract);
        }

        private JsonNode last() {
            assertFalse(received.isEmpty(), "nothing reached viewer " + connection.id());
            return received.getLast();
        }
    }

    private void send(Viewer viewer, String frame) {
        service.onClientFrame(JOB, viewer.connection, read(frame));
    }

    private void fromAgent(String frame) {
        service.onFrame(WORKER, JOB, read(frame));
    }

    private void askPermission(String id) {
        fromAgent("""
                {"jsonrpc":"2.0","id":"%s","method":"session/request_permission",
                 "params":{"sessionId":"%s","toolCall":{}}}""".formatted(id, ROOT));
    }

    private static String prompt(int id, String session) {
        return """
                {"jsonrpc":"2.0","id":%s,"method":"session/prompt",
                 "params":{"sessionId":"%s","prompt":[]}}""".formatted(id, session);
    }

    private static String permission(long id, String outcome) {
        return """
                {"jsonrpc":"2.0","id":%s,"result":{"outcome":"%s"}}""".formatted(id, outcome);
    }

    private List<JsonNode> allToAgent() {
        var frames = ArgumentCaptor.forClass(JsonNode.class);
        verify(workerService, atLeastOnce()).sendAgentFrame(eq(WORKER), eq(JOB), frames.capture());
        return frames.getAllValues();
    }

    private JsonNode lastToAgent() {
        return allToAgent().getLast();
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(json, e);
        }
    }
}
