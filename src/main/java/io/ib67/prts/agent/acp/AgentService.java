package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import io.ib67.prts.Perm;
import io.ib67.prts.agent.acp.entity.AgentDirection;
import io.ib67.prts.agent.worker.WorkerEvent;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.message.ServerboundMessage;
import io.quarkus.vertx.ConsumeEvent;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Relays ACP JSON-RPC traffic between worker-hosted agents and connected viewers.
 *
 * <p>Enforces the {@link AcpMethod} allowlist, translates connection-scoped request IDs, and
 * persists frames via {@link AgentTranscript} sequentially before forwarding. The channels
 * themselves live in {@link AgentChannels}.
 * Outbound frame dispatches occur outside transactions to avoid holding locks during network I/O.
 *
 * <p>See {@code agent-docs/agent-acp.md}.
 */
@ApplicationScoped
public class AgentService {

    private static final Logger LOG = Logger.getLogger(AgentService.class);

    /**
     * Application WebSocket close code: no active agent session.
     */
    static final int NO_AGENT = 4404;
    /**
     * The caller may not watch this job's agent.
     */
    static final int FORBIDDEN = 4403;
    static final int TOO_MANY_VIEWERS = 4429;

    private static final String NO_OPERATOR = "no operator is attached to this job";
    private static final String UNREACHABLE = "the job's worker could not be reached";

    @Inject
    WorkerService workerService;
    @Inject
    AgentChannels channels;
    @Inject
    AgentTranscript transcript;
    @Inject
    AcpConfig acpConfig;

    // WorkerWebSocket already answered the worker; a failure here reaches nobody but the log.
    // Blocking: the transcript is written from here. Ordered: a job's frames must reach the
    // transcript and the viewers in the order the worker sent them.
    @ConsumeEvent(value = WorkerEvent.SERVERBOUND_EVENT, blocking = true, ordered = true)
    void onWorkerEvent(WorkerEvent.C2S message) {
        try {
            switch (message.message()) {
                case ServerboundMessage.AgentAttached a ->
                        onAttached(message.worker(), a.jobId(), a.initialize(), a.sessionId());
                case ServerboundMessage.AgentFrame f -> onFrame(message.worker(), f.jobId(), f.frame());
                case ServerboundMessage.AgentDetached d -> onDetached(message.worker(), d.jobId(), d.reason());
                default -> {
                }
            }
        } catch (Exception ex) {
            //todo better logging
            LOG.error("error occurred when handling event from %s", message.worker(), ex);
        }
    }

    void onAttached(UUID workerId, UUID jobId, JsonNode initialize, String acpSessionId) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(initialize, "initialize");
        if (acpSessionId == null || acpSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        channels.open(workerId, jobId, initialize, acpSessionId);
    }

    void onFrame(UUID workerId, UUID jobId, JsonNode node) {
        var channel = channels.require(workerId, jobId);
        var frame = AcpFrame.of(node);
        if (frame.isResponse()) {
            fromAgentResponse(channel, frame);
        } else {
            fromAgentCall(channel, frame);
        }
    }

    void onDetached(UUID workerId, UUID jobId, @Nullable String reason) {
        channels.require(workerId, jobId);
        channels.close(jobId, reason == null || reason.isBlank() ? "the agent detached" : reason);
    }

    /**
     * Tears down all agent channels associated with a disconnected worker.
     */
    // Blocking: closing a channel closes its sessions in the transcript.
    @ConsumeEvent(value = WorkerEvent.OFFLINE, blocking = true)
    void onWorkerGone(UUID workerId) {
        channels.closeByWorker(workerId);
    }

    /**
     * Closes a job's channel upon entering a terminal state. Must be called post-commit.
     */
    public void onJobClosed(UUID jobId) {
        channels.close(jobId, "the job finished");
    }

    private void fromAgentResponse(AgentChannel channel, AcpFrame frame) {
        var id = numericId(frame.id());
        var pending = id == null ? null : channel.completeAgent(id);
        if (pending == null) {
            LOG.debugf("job %s: its agent answered %s, which nobody is waiting on",
                    channel.jobId(), frame.id());
            return;
        }
        transcript.record(pending.session(), AgentDirection.FROM_AGENT, null, null, frame.json());
        var viewer = channel.viewer(pending.viewer());
        if (viewer == null) {
            LOG.debugf("job %s: the viewer that asked %s has left", channel.jobId(), id);
            return;
        }
        viewer.send(frame.withId(pending.originalId()));
    }

    private void fromAgentCall(AgentChannel channel, AcpFrame frame) {
        var method = AcpMethod.byMethod(frame.method()).orElse(null);
        if (method == null || !method.route().acceptsFromAgent()) {
            // fs/* and terminal/* belong to the worker, which holds the container; answering keeps the
            // agent from waiting on a browser that was never going to serve them.
            refuseAgent(channel, frame, AcpFrame.METHOD_NOT_FOUND,
                    "prts-central does not carry " + frame.method());
            return;
        }
        var session = sessionOf(channel, frame);
        if (session == null) {
            refuseAgent(channel, frame, AcpFrame.INVALID_PARAMS,
                    "job " + channel.jobId() + " is at its limit of "
                            + acpConfig.maxSessionsPerJob() + " sessions");
            return;
        }
        transcript.record(session, AgentDirection.FROM_AGENT, frame.method(), null, frame.json());
        if (frame.isNotification()) {
            channel.broadcast(frame);
            return;
        }
        if (channel.viewerCount() == 0) {
            answerAgent(channel, AcpFrame.error(frame.id(), AcpFrame.INTERNAL_ERROR, NO_OPERATOR));
            return;
        }
        channel.broadcastRequest(frame, session);
    }

    /**
     * Resolves the session for an agent frame, registering unseen subagent sessions under root.
     *
     * @return null if the job reached its session limit
     */
    @Nullable
    private UUID sessionOf(AgentChannel channel, AcpFrame frame) {
        var acpSessionId = frame.sessionId();
        if (acpSessionId == null) {
            return channel.rootSession();
        }
        var known = channel.session(acpSessionId);
        if (known != null) {
            return known;
        }
        if (channel.sessionCount() >= acpConfig.maxSessionsPerJob()) {
            LOG.warnf("job %s: refusing session %s, it already holds %s",
                    channel.jobId(), acpSessionId, channel.sessionCount());
            return null;
        }
        var opened = transcript.openChild(channel.rootSession(), acpSessionId);
        channel.putSession(acpSessionId, opened);
        LOG.debugf("job %s: recording subagent session %s", channel.jobId(), acpSessionId);
        return opened;
    }

    private void refuseAgent(AgentChannel channel, AcpFrame frame, int code, String reason) {
        if (frame.isRequest()) {
            answerAgent(channel, AcpFrame.error(frame.id(), code, reason));
        } else {
            LOG.debugf("job %s: dropped a notification, %s", channel.jobId(), reason);
        }
    }

    private void answerAgent(AgentChannel channel, AcpFrame frame) {
        if (!forwardToAgent(channel, frame)) {
            LOG.warnf("job %s: its agent is waiting on an answer that could not be delivered",
                    channel.jobId());
        }
    }

    /**
     * Hands one frame to the job's worker.
     *
     * <p>The worker's acknowledgment is not waited for: this runs while a viewer or an agent frame
     * is being served, and a round trip per frame would stall that path. The return value says the
     * worker was connected and the frame went out.
     */
    private boolean forwardToAgent(AgentChannel channel, AcpFrame frame) {
        try {
            workerService.sendAgentFrame(channel.workerId(), channel.jobId(), frame.json())
                    .exceptionally(failure -> {
                        LOG.debugf(failure, "job %s: the worker did not acknowledge a frame", channel.jobId());
                        return null;
                    });
            return true;
        } catch (RuntimeException e) {
            LOG.errorf(e, "job %s: cannot reach its agent on worker %s",
                    channel.jobId(), channel.workerId());
            return false;
        }
    }

    // ---------------------------------------------------------------- the viewers' side

    /**
     * Registers a viewer for a job's live agent channel.
     *
     * @param mayInteract whether the viewer holds {@code job:agent:interact}
     * @throws NoSuchElementException if no active channel exists for the job/project
     * @throws IllegalStateException  if the channel reached its viewer limit
     */
    public void attach(
            UUID projectId, UUID jobId, WebSocketConnection connection, UUID userId, boolean mayInteract) {
        channels.attach(projectId, jobId, new AgentViewer(connection, userId, mayInteract));
    }

    /**
     * Removes a viewer. Fails any pending agent requests if no viewers remain.
     */
    public void detach(UUID jobId, WebSocketConnection connection) {
        var channel = channels.get(jobId);
        if (channel == null || channel.removeViewer(connection.id()) == null) {
            return;
        }
        if (channel.viewerCount() > 0) {
            return;
        }
        channel.drainClient().values().forEach(pending -> answerAgent(
                channel, AcpFrame.error(pending.originalId(), AcpFrame.INTERNAL_ERROR, NO_OPERATOR)));
    }

    /**
     * Handles one JSON-RPC frame a viewer sent.
     */
    void onClientFrame(UUID jobId, WebSocketConnection connection, JsonNode node) {
        var channel = channels.get(jobId);
        if (channel == null || channel.isClosed()) {
            AgentViewer.sendTo(connection, AcpFrame.error(
                    null, AcpFrame.INTERNAL_ERROR, "job " + jobId + " has no live agent session"));
            return;
        }
        var viewer = channel.viewer(connection.id());
        if (viewer == null) {
            return;
        }
        AcpFrame frame;
        try {
            frame = AcpFrame.of(node);
        } catch (IllegalArgumentException e) {
            viewer.send(AcpFrame.error(null, AcpFrame.INVALID_REQUEST, e.getMessage()));
            return;
        }
        if (frame.isResponse()) {
            fromClientResponse(channel, viewer, frame);
        } else {
            fromClientCall(channel, viewer, frame);
        }
    }

    private void fromClientResponse(AgentChannel channel, AgentViewer viewer, AcpFrame frame) {
        if (!viewer.mayInteract()) {
            viewer.send(AcpFrame.error(frame.id(), AcpFrame.FORBIDDEN, missing()));
            return;
        }
        var id = numericId(frame.id());
        // Removing the entry claims the response (first viewer wins).
        var pending = id == null ? null : channel.completeClient(id);
        if (pending == null) {
            LOG.debugf("job %s: viewer %s answered %s, which someone else already did",
                    channel.jobId(), viewer.id(), frame.id());
            return;
        }
        var answer = frame.withId(pending.originalId());
        transcript.record(
                pending.session(), AgentDirection.FROM_CLIENT, null, viewer.userId(), answer.json());
        answerAgent(channel, answer);
    }

    private void fromClientCall(AgentChannel channel, AgentViewer viewer, AcpFrame frame) {
        var method = AcpMethod.byMethod(frame.method()).orElse(null);
        if (method == null || !method.route().acceptsFromClient()) {
            viewer.refuse(frame, AcpFrame.METHOD_NOT_FOUND,
                    "prts-central does not carry " + frame.method());
            return;
        }
        if (method.route() == AcpMethod.Route.LOCAL) {
            serveLocally(channel, viewer, method, frame);
            return;
        }
        if (!viewer.mayInteract()) {
            viewer.refuse(frame, AcpFrame.FORBIDDEN, missing());
            return;
        }
        var acpSessionId = frame.sessionId();
        var session = channel.session(acpSessionId);
        if (session == null) {
            viewer.refuse(frame, AcpFrame.INVALID_PARAMS, acpSessionId == null
                    ? "sessionId is required"
                    : "no such session in job " + channel.jobId() + ": " + acpSessionId);
            return;
        }
        if (frame.isNotification()) {
            transcript.record(
                    session, AgentDirection.FROM_CLIENT, frame.method(), viewer.userId(), frame.json());
            forwardToAgent(channel, frame);
            return;
        }
        var id = channel.nextId();
        var forwarded = frame.withId(LongNode.valueOf(id));
        transcript.record(
                session, AgentDirection.FROM_CLIENT, frame.method(), viewer.userId(), forwarded.json());
        channel.awaitAgent(id, new AgentChannel.ToAgent(viewer.id(), frame.id(), session));
        if (!forwardToAgent(channel, forwarded)) {
            channel.completeAgent(id);
            viewer.send(AcpFrame.error(frame.id(), AcpFrame.INTERNAL_ERROR, UNREACHABLE));
        }
    }

    private static void serveLocally(
            AgentChannel channel, AgentViewer viewer, AcpMethod method, AcpFrame frame) {
        if (!frame.isRequest()) {
            LOG.debugf("viewer %s sent %s as a notification, which answers nothing",
                    viewer.id(), method.method());
            return;
        }
        switch (method) {
            case INITIALIZE -> viewer.send(AcpFrame.result(frame.id(), channel.initializeWithMeta()));
            default -> viewer.refuse(frame, AcpFrame.METHOD_NOT_FOUND,
                    "prts-central does not carry " + frame.method());
        }
    }

    private static String missing() {
        return "missing permission: " + Perm.JOB_AGENT_INTERACT.permission();
    }

    @Nullable
    private static Long numericId(@Nullable JsonNode id) {
        return id != null && id.canConvertToLong() ? id.asLong() : null;
    }
}
