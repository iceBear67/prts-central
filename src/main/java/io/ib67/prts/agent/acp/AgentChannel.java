package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.websockets.next.CloseReason;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory bidirectional proxy between a job's agent and attached viewers.
 *
 * <p>Manages JSON-RPC ID translation across multiple viewer connections multiplexed over a single
 * worker link. Pending request maps are keyed by central-allocated IDs; removing an entry claims the
 * response (first-viewer-wins for agent requests).
 */
final class AgentChannel {

    private final UUID jobId;
    private final UUID projectId;
    private final UUID workerId;
    /** Cached initialize result from worker, replayed to joining viewers. */
    private final JsonNode initialize;
    private final String rootSessionId;
    private final UUID rootSession;

    /** Maps ACP session IDs to persisted AgentSession row IDs. */
    private final Map<String, UUID> sessions = new ConcurrentHashMap<>();
    private final Map<String, AgentViewer> viewers = new ConcurrentHashMap<>();

    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, ToAgent> toAgent = new ConcurrentHashMap<>();
    private final Map<Long, ToClient> toClient = new ConcurrentHashMap<>();

    private volatile boolean closed;

    AgentChannel(
            UUID jobId,
            UUID projectId,
            UUID workerId,
            JsonNode initialize,
            String rootSessionId,
            UUID rootSession) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.initialize = Objects.requireNonNull(initialize, "initialize");
        this.rootSessionId = Objects.requireNonNull(rootSessionId, "rootSessionId");
        this.rootSession = Objects.requireNonNull(rootSession, "rootSession");
        sessions.put(rootSessionId, rootSession);
    }

    /** Pending viewer request awaiting agent response. */
    record ToAgent(String viewer, JsonNode originalId, UUID session) {
        ToAgent {
            Objects.requireNonNull(viewer, "viewer");
            Objects.requireNonNull(originalId, "originalId");
            Objects.requireNonNull(session, "session");
        }
    }

    /** Pending agent request awaiting the first responding viewer. */
    record ToClient(JsonNode originalId, UUID session) {
        ToClient {
            Objects.requireNonNull(originalId, "originalId");
            Objects.requireNonNull(session, "session");
        }
    }

    UUID jobId() {
        return jobId;
    }

    UUID projectId() {
        return projectId;
    }

    UUID workerId() {
        return workerId;
    }

    UUID rootSession() {
        return rootSession;
    }

    boolean isClosed() {
        return closed;
    }

    long nextId() {
        return ids.incrementAndGet();
    }

    /**
     * Drops every viewer and abandons every request the agent is waiting on, since the agent it was
     * waiting on is gone too.
     */
    void discard(String reason) {
        closed = true;
        toClient.clear();
        var closing = new CloseReason(CloseReason.NORMAL.getCode(), reason);
        var dropped = List.copyOf(viewers.values());
        viewers.clear();
        dropped.forEach(viewer -> viewer.close(closing));
    }

    /**
     * The worker's {@code initialize} snapshot, augmented with PRTS metadata under {@code _meta.prts}.
     */
    JsonNode initializeWithMeta() {
        if (!initialize.isObject()) {
            return initialize;
        }
        var result = (ObjectNode) initialize.deepCopy();
        var existing = result.get("_meta");
        var meta = existing != null && existing.isObject()
                ? (ObjectNode) existing
                : result.putObject("_meta");
        var prts = meta.putObject("prts");
        prts.put("jobId", jobId.toString());
        prts.put("projectId", projectId.toString());
        prts.put("rootSessionId", rootSessionId);
        var listed = prts.putArray("sessions");
        sessions.forEach((acpSessionId, row) -> {
            var entry = listed.addObject();
            entry.put("sessionId", acpSessionId);
            entry.put("id", row.toString());
            entry.put("root", acpSessionId.equals(rootSessionId));
        });
        return result;
    }

    // ---- sessions ----

    /** Persisted session ID, or null if unknown. */
    @Nullable
    UUID session(@Nullable String acpSessionId) {
        return acpSessionId == null ? null : sessions.get(acpSessionId);
    }

    void putSession(String acpSessionId, UUID session) {
        sessions.put(acpSessionId, session);
    }

    /** Restores existing session mappings across re-attachment. */
    void putSessions(Map<String, UUID> known) {
        sessions.putAll(known);
    }

    int sessionCount() {
        return sessions.size();
    }

    // ---- viewers ----

    void addViewer(AgentViewer viewer) {
        viewers.put(viewer.id(), viewer);
    }

    @Nullable
    AgentViewer removeViewer(String connectionId) {
        return viewers.remove(connectionId);
    }

    @Nullable
    AgentViewer viewer(String connectionId) {
        return viewers.get(connectionId);
    }

    int viewerCount() {
        return viewers.size();
    }

    void broadcast(AcpFrame frame) {
        viewers.values().forEach(viewer -> viewer.send(frame));
    }

    // ---- pending requests ----

    void awaitAgent(long id, ToAgent pending) {
        toAgent.put(id, pending);
    }

    /** Claims a viewer's pending request, or null if already answered or abandoned. */
    @Nullable
    ToAgent completeAgent(long id) {
        return toAgent.remove(id);
    }

    /**
     * Broadcasts an agent request under a central-allocated ID, which the first viewer to answer claims.
     */
    void broadcastRequest(AcpFrame frame, UUID session) {
        var id = nextId();
        toClient.put(id, new ToClient(frame.id(), session));
        broadcast(frame.withId(LongNode.valueOf(id)));
    }

    /** Claims an agent's pending request for the first answering viewer. */
    @Nullable
    ToClient completeClient(long id) {
        return toClient.remove(id);
    }

    /** Drains all pending agent requests. */
    Map<Long, ToClient> drainClient() {
        var drained = Map.copyOf(toClient);
        toClient.clear();
        return drained;
    }
}
