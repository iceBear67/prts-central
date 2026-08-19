package io.ib67.prts.agent.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.runner.message.ClientboundMessage;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;

public record RunnerRpc(WebSocketConnection conn, ObjectMapper mapper) {
    public Uni<Void> sendMessage(ClientboundMessage message) {
        return conn().sendText(message);
    }
}
