package io.ib67.prts.agent.acp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.Perm;
import io.ib67.prts.job.JobAccess;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.user.UserContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * WebSocket endpoint for browser clients interacting with a job's ACP agent.
 *
 * <p>Passes raw JSON-RPC frames directly to {@link AgentService}. Handshake authentication is enforced
 * by Quarkus HTTP security; authorization is checked programmatically here via {@link JobAccess}
 * because {@code @RequirePermission} requires JAX-RS request context. Unauthorized connections
 * close with application codes (4403/4404/4429).
 */
@WebSocket(path = "/ws/project/{projectId}/job/{jobId}/agent")
public class AgentWebSocket {

    private static final Logger LOG = Logger.getLogger(AgentWebSocket.class);
    /** Marks an active viewer connection. */
    private static final UserData.TypedKey<String> ATTACHED_JOB = UserData.TypedKey.forString("acp_job");

    @Inject
    WebSocketConnection connection;
    @Inject
    AgentService agentService;
    @Inject
    JobAccess jobAccess;
    @Inject
    ProjectService projectService;
    @Inject
    UserContext userContext;
    @Inject
    ObjectMapper mapper;

    @OnOpen
    @Blocking
    public void onOpen() {
        UUID projectId;
        UUID jobId;
        try {
            projectId = UUID.fromString(connection.pathParam("projectId"));
            jobId = UUID.fromString(connection.pathParam("jobId"));
        } catch (IllegalArgumentException | NullPointerException e) {
            close(AgentService.NO_AGENT, "malformed project or job id");
            return;
        }
        var user = userContext.get();
        if (user == null) {
            close(AgentService.FORBIDDEN, "not authenticated");
            return;
        }
        if (!jobAccess.mayReadAgent(projectId)) {
            // Non-members receive FORBIDDEN to avoid disclosing project existence.
            close(AgentService.FORBIDDEN, "missing permission: " + Perm.JOB_AGENT_READ.permission());
            return;
        }
        boolean archived;
        try {
            archived = QuarkusTransaction.requiringNew()
                    .call(() -> projectService.require(projectId).isArchived());
        } catch (NoSuchElementException e) {
            close(AgentService.NO_AGENT, e.getMessage());
            return;
        }
        // Archived projects allow viewing but forbid interaction.
        var mayInteract = !archived && jobAccess.mayInteractWithAgent(projectId);
        try {
            agentService.attach(projectId, jobId, connection, user.getId(), mayInteract);
        } catch (NoSuchElementException e) {
            close(AgentService.NO_AGENT, e.getMessage());
            return;
        } catch (IllegalStateException e) {
            close(AgentService.TOO_MANY_VIEWERS, e.getMessage());
            return;
        }
        connection.userData().put(ATTACHED_JOB, jobId.toString());
        LOG.debugf("user %s is watching the agent of job %s", user.getId(), jobId);
    }

    @OnTextMessage
    @Blocking
    public void onMessage(String text) {
        var jobId = connection.userData().get(ATTACHED_JOB);
        if (jobId == null) {
            return;
        }
        try {
            agentService.onClientFrame(UUID.fromString(jobId), connection, mapper.readTree(text));
        } catch (JsonProcessingException e) {
            AgentViewer.sendTo(connection, AcpFrame.error(
                    null, AcpFrame.PARSE_ERROR, "malformed JSON: " + e.getOriginalMessage()));
        }
    }

    @OnClose
    @Blocking
    public void onClose() {
        var jobId = connection.userData().get(ATTACHED_JOB);
        if (jobId != null) {
            agentService.detach(UUID.fromString(jobId), connection);
        }
    }

    @OnError
    public void onError(Throwable error) {
        LOG.errorf(error, "cannot handle a frame from viewer %s", connection.id());
        close(CloseReason.INTERNAL_SERVER_ERROR.getCode(), "the frame could not be handled");
    }

    private void close(int code, String reason) {
        connection.close(new CloseReason(code, reason)).subscribe().with(
                closed -> {
                },
                failure -> LOG.debugf(failure, "cannot close viewer %s", connection.id()));
    }
}
