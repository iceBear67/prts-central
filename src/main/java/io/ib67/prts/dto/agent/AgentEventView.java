package io.ib67.prts.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.ib67.prts.agent.acp.entity.AgentDirection;
import io.ib67.prts.dto.UserInfo;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * One recorded JSON-RPC frame of an ACP session.
 *
 * @param method null for responses
 * @param actor  viewer user info, or null if sent by the agent
 * @param frame  verbatim JSON-RPC frame
 */
public record AgentEventView(
        long id,
        Instant createdAt,
        AgentDirection direction,
        @Nullable String method,
        @Nullable UserInfo actor,
        JsonNode frame
) {
    public AgentEventView {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frame, "frame");
    }
}
