package io.ib67.prts.agent.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.runner.message.ClientboundMessage;
import io.ib67.prts.agent.runner.message.ServerboundMessage;
import io.ib67.prts.project.JobService;
import io.quarkus.websockets.next.*;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;

import java.util.NoSuchElementException;
import java.util.UUID;

@WebSocket(path = "/ws/runner") //todo custom auth
public class RunnerWebSocket {
    private static final UserData.TypedKey<String> INTERNAL_RUNNER_ID = UserData.TypedKey.forString("runner_id");
    @Inject
    WebSocketConnection connection;
    @Inject
    RunnerService runnerService;
    @Inject
    JobService jobService;
    @Inject
    ObjectMapper mapper;

    @OnClose
    public void onClose() {
        var idStr = connection.userData().get(INTERNAL_RUNNER_ID);
        if (idStr == null) return;
        runnerService.unregisterRunner(UUID.fromString(idStr));
    }

    @OnTextMessage
    @Blocking
    public ClientboundMessage acceptMessage(ServerboundMessage message) {
        return switch (message) {
            case ServerboundMessage.Register r -> handleRunnerRegister(r);
            case ServerboundMessage.UpdateJobLog u -> handleUpdateJobLog(u);
        };
    }

    private ClientboundMessage handleRunnerRegister(ServerboundMessage.Register r) {
        if (connection.userData().get(INTERNAL_RUNNER_ID) != null)
            return new ClientboundMessage.Response(false, "already registered on this connection");
        var result = runnerService.registerRunner(r.id(), new RunnerService.Runner(r.name(), new RunnerRpc(connection, mapper), r.info()));
        if (result) {
            connection.userData().put(INTERNAL_RUNNER_ID, r.id().toString());
        }
        return new ClientboundMessage.Response(result, "");
    }

    private ClientboundMessage handleUpdateJobLog(ServerboundMessage.UpdateJobLog u) {
        if (connection.userData().get(INTERNAL_RUNNER_ID) == null) {
            return new ClientboundMessage.Response(false, "not registered");
        }
        try {
            jobService.appendLog(u.jobId(), u.topic(), u.message(), u.error());
            return new ClientboundMessage.Response(true, "");
        } catch (NoSuchElementException | IllegalStateException e) {
            return new ClientboundMessage.Response(false, e.getMessage());
        }
    }
}
