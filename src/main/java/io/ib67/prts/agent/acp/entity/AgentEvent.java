package io.ib67.prts.agent.acp.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.Nullable;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persisted ACP JSON-RPC frame.
 *
 * <p>Uses an identity {@code bigint} primary key (like {@code JobLog}) for high-frequency chunk insertion
 * and deterministic sequence ordering where timestamps lack sufficient resolution.
 */
@Entity
@Table(
        name = "agent_event",
        indexes = @Index(name = "idx_agent_event_session", columnList = "session_id, id"),
        check = @CheckConstraint(
                name = "agent_event_direction_values",
                constraint = "direction IN ('FROM_AGENT', 'FROM_CLIENT')")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AgentEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    // Avoids colliding with PanacheEntityBase.getSession() via Lombok getter.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private AgentSession agentSession;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, columnDefinition = "varchar")
    private AgentDirection direction;

    /** JSON-RPC method, or null for responses. */
    @Nullable
    @Column(name = "method", updatable = false, columnDefinition = "varchar")
    private String method;

    /** Viewer user ID, or null if sent by the agent. */
    @Nullable
    @Column(name = "actor", updatable = false)
    private UUID actor;

    /** Verbatim frame payload as seen at the agent boundary. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "frame", nullable = false, updatable = false, columnDefinition = "jsonb")
    @ToString.Exclude
    private JsonNode frame;

    /** Lists a paginated range of session frames in chronological order. */
    public static List<AgentEvent> listBySession(UUID sessionId, int offset, int length) {
        return find("agentSession.id", Sort.by("id"), sessionId).range(offset, offset + length - 1).list();
    }

    public static long countBySession(UUID sessionId) {
        return count("agentSession.id", sessionId);
    }
}
