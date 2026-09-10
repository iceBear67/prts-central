package io.ib67.prts.job.task.entity;

import io.ib67.prts.job.entity.Project;
import io.ib67.prts.job.task.TaskScope;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A shared scope for a group of jobs within a project.
 *
 * <p>A task groups jobs that belong to one topic and gives them a common substrate — mounted volumes,
 * environment, labels, a default resource class. It does not orchestrate them: it declares no steps,
 * resolves no dependencies, and never creates a job on its own.
 */
@Entity
@Table(
        name = "task",
        indexes = @Index(name = "idx_task_project_id", columnList = "project_id"),
        check = {
                @CheckConstraint(
                        name = "task_state_values",
                        constraint = "state IN ('OPEN', 'CLOSING', 'CLOSED')"
                ),
                @CheckConstraint(
                        name = "task_closure_consistency",
                        constraint = "(state <> 'CLOSED' AND closed_at IS NULL) "
                                + "OR (state = 'CLOSED' AND closed_at IS NOT NULL)"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Task extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    /**
     * What the task is about; empty string if nobody said.
     *
     * <p>Carries a DDL default so {@code schema-management.strategy: update} can add the column to a
     * database that already holds task rows.
     */
    @Builder.Default
    @Column(name = "description", nullable = false, columnDefinition = "varchar default ''")
    private String description = "";

    /**
     * Where this task came from — an issue, a pull request, a ticket. Empty string if it tracks nothing.
     *
     * <p>A link for people to follow, nothing more: the control plane never fetches it, and it reaches
     * neither the job spec nor the worker.
     */
    @Builder.Default
    @Column(name = "tracked_at", nullable = false, columnDefinition = "varchar default ''")
    private String trackedAt = "";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, columnDefinition = "varchar")
    private TaskState state = TaskState.OPEN;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope", nullable = false, columnDefinition = "jsonb")
    @ToString.Exclude
    private TaskScope scope = TaskScope.EMPTY;

    /** User ID that opened the task. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until teardown finishes. */
    @Nullable
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Transitions the task and keeps {@link #closedAt} in step with the DB check constraint
     * {@code task_closure_consistency}.
     */
    public void transitionTo(TaskState next) {
        this.state = Objects.requireNonNull(next, "state");
        this.closedAt = next.isClosed() ? Instant.now() : null;
    }

    /** Lists a project's tasks, most recent first. */
    public static List<Task> listByProject(UUID projectId, int offset, int limit) {
        return Task.<Task>find("project.id = ?1 order by createdAt desc, id desc", projectId)
                .range(offset, offset + limit - 1)
                .list();
    }

    public static Optional<Task> findInProject(UUID projectId, UUID taskId) {
        return Task.<Task>findByIdOptional(taskId)
                .filter(task -> task.getProject().getId().equals(projectId));
    }

    /** Tasks whose teardown has not finished, oldest first. */
    public static List<Task> listClosing(int limit) {
        return Task.<Task>find("state = ?1 order by createdAt, id", TaskState.CLOSING)
                .page(0, limit)
                .list();
    }

    public static long countOpenByProject(UUID projectId) {
        return count("project.id = ?1 and state = ?2", projectId, TaskState.OPEN);
    }
}
