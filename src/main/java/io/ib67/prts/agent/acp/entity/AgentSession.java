package io.ib67.prts.agent.acp.entity;

import io.ib67.prts.job.entity.Job;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ACP session associated with a job.
 *
 * <p>Each job has exactly one root session ({@code parent} is null). All subsequent subagent sessions
 * are parented directly to the root as ACP does not expose hierarchical parent-child relationships.
 */
@Entity
@Table(
        name = "agent_session",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_agent_session_acp_id", columnNames = {"job_id", "acp_session_id"}),
        indexes = @Index(name = "idx_agent_session_job", columnList = "job_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AgentSession extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Job job;

    /** Agent-assigned session identifier; unique within the job. */
    @Column(name = "acp_session_id", nullable = false, updatable = false, columnDefinition = "varchar")
    private String acpSessionId;

    /** Parent root session, or null if this is the root session. */
    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id", updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private AgentSession parent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Closure timestamp, or null if active. */
    @Nullable
    @Column(name = "closed_at")
    private Instant closedAt;

    private static Sort chronological() {
        return Sort.by("createdAt").and("id");
    }

    /** Lists a job's sessions oldest first, root session leading. */
    public static List<AgentSession> listByJob(UUID jobId) {
        return find("job.id", chronological(), jobId).list();
    }

    public static Optional<AgentSession> findInJob(UUID jobId, String acpSessionId) {
        return find("job.id = ?1 and acpSessionId = ?2", jobId, acpSessionId).firstResultOptional();
    }

    /** Resolves a session by ID within a job and project. */
    public static Optional<AgentSession> findInProject(UUID projectId, UUID jobId, UUID sessionId) {
        return find("id = ?1 and job.id = ?2 and job.project.id = ?3", sessionId, jobId, projectId)
                .firstResultOptional();
    }

    /** Closes all open sessions for a job. */
    public static int closeOpenByJob(UUID jobId, Instant at) {
        return update("closedAt = ?1 where job.id = ?2 and closedAt is null", at, jobId);
    }
}
