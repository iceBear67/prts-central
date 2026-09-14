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
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Context grouping a set of jobs under a common project, with shared environment, labels, and volumes.
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
     * Task description; defaults to empty string.
     */
    @Builder.Default
    @Column(name = "description", nullable = false, columnDefinition = "varchar default ''")
    private String description = "";

    /**
     * Issue or pull request link associated with this task; empty string if omitted.
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
     * Transitions the task to the given state and updates {@link #closedAt}.
     */
    public void transitionTo(TaskState next) {
        this.state = Objects.requireNonNull(next, "state");
        this.closedAt = next.isClosed() ? Instant.now() : null;
    }

    /** Lists a project's tasks, most recent first. */
    public static List<Task> listByProject(UUID projectId, int offset, int limit) {
        return Task.<Task>find("from Task t join fetch t.project" + " where t.project.id = ?1 order by t.createdAt desc, t.id desc",
                        projectId)
                .range(offset, offset + limit - 1)
                .list();
    }

    public static Optional<Task> findInProject(UUID projectId, UUID taskId) {
        return Task.<Task>find("from Task t join fetch t.project" + " where t.id = ?1 and t.project.id = ?2", taskId, projectId)
                .firstResultOptional();
    }

    /**
     * Lists one page of tasks across every project, most recent first, matching {@code query} against
     * the task name and narrowing to {@code state} when either is given.
     */
    public static List<Task> search(
            @Nullable String query, @Nullable TaskState state, int offset, int limit) {
        var filter = query == null || query.isBlank() ? "%" : "%" + query.strip().toLowerCase() + "%";
        var narrowed = state == null ? "" : " and t.state = :state";
        var parameters = new HashMap<String, Object>();
        parameters.put("name", filter);
        if (state != null) {
            parameters.put("state", state);
        }
        return Task.<Task>find("from Task t join fetch t.project" + " where lower(t.name) like :name" + narrowed
                        + " order by t.createdAt desc, t.id desc", parameters)
                .range(offset, offset + limit - 1)
                .list();
    }

    /** Returns closing tasks awaiting teardown, ordered by creation time. */
    public static List<Task> listClosing(int limit) {
        return Task.<Task>find("state = ?1 order by createdAt, id", TaskState.CLOSING)
                .page(0, limit)
                .list();
    }

    public static long countOpenByProject(UUID projectId) {
        return count("project.id = ?1 and state = ?2", projectId, TaskState.OPEN);
    }
}
