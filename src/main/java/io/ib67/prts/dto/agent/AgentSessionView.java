package io.ib67.prts.dto.agent;

import io.ib67.prts.agent.acp.entity.AgentSession;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View of an ACP session.
 *
 * @param sessionId agent-assigned session identifier
 * @param parent    parent session ID, or null if root
 * @param closedAt  closure timestamp, or null if active
 */
public record AgentSessionView(
        UUID id,
        String sessionId,
        @Nullable UUID parent,
        Instant createdAt,
        @Nullable Instant closedAt
) {
    public AgentSessionView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static AgentSessionView of(AgentSession session) {
        return new AgentSessionView(
                session.getId(),
                session.getAcpSessionId(),
                session.getParent() == null ? null : session.getParent().getId(),
                session.getCreatedAt(),
                session.getClosedAt());
    }
}
