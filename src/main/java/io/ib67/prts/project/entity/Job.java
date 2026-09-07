package io.ib67.prts.project.entity;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.agent.worker.entity.ResourceClass;
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
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single job execution within a project.
 */
@Entity
@Table(
        name = "job",
        indexes = @Index(name = "idx_job_project_id", columnList = "project_id"),
        check = {
                @CheckConstraint(
                        name = "job_state_values",
                        constraint = "state IN ('PENDING', 'RUNNING', 'FAILED', 'SUCCESS', 'CANCELLED')"
                ),
                @CheckConstraint(
                        name = "job_completion_consistency",
                        constraint = "((state = 'PENDING' OR state = 'RUNNING') AND completed_at IS NULL) "
                                + "OR (state IN ('SUCCESS', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)"
                )
        }
)
// DynamicUpdate prevents concurrent field updates from overwriting each other.
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Job extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until the job reaches a terminal {@link JobState}. */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, columnDefinition = "varchar")
    private JobState state = JobState.PENDING;

    private UUID worker;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", columnDefinition = "jsonb")
    @ToString.Exclude
    private JobSpec spec;

    /** The resource class assigned to this job, resolved at creation time. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumns({
            @JoinColumn(name = "resource_class", referencedColumnName = "name", nullable = false),
            @JoinColumn(name = "resource_class_project", referencedColumnName = "project_id",
                    nullable = false)
    })
    @ToString.Exclude
    private ResourceClass resourceClass;

    /** Template ID used to create this job, or {@code null} if not created from a template. */
    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    /** User ID of the requester. */
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "create_override", updatable = false, columnDefinition = "jsonb")
    @ToString.Exclude
    private JobSpecOverride createOverride;

    /**
     * Constructs a {@link JobRequest} to re-run this job, or {@code null} if this job cannot be replayed.
     */
    @Nullable
    public JobRequest toRequest() {
        return templateId == null ? null : new JobRequest(templateId, createOverride, resourceClass.getName());
    }

    /**
     * Transitions the job to the specified state and updates {@link #completedAt} accordingly.
     */
    public void transitionTo(JobState next) {
        this.state = Objects.requireNonNull(next, "state");
        this.completedAt = next.isTerminal() ? Instant.now() : null;
    }

    public boolean isCompleted() {
        return state != null && state.isTerminal();
    }

    // Only jobs that have been assigned to a worker or are no longer pending are visible to readers.
    private static final String VISIBLE_ROW = "(state <> :pending or worker is not null)";
    private static final String VISIBLE = "project.id = :project and " + VISIBLE_ROW;

    /** Lists visible jobs for a project in reverse chronological order. */
    public static List<Job> listVisibleByProject(UUID projectId, int limit) {
        return find(VISIBLE + " order by createdAt desc, id desc",
                Map.of("project", projectId, "pending", JobState.PENDING))
                .page(0, limit)
                .list();
    }

    /** Job count summary for a project. */
    public record Counts(long visible, long running) {
    }

    public static Counts countByProject(UUID projectId) {
        var row = (Object[]) Job.getEntityManager()
                .createQuery("select count(case when " + VISIBLE_ROW + " then 1 end), "
                        + "count(case when worker is not null and state in :open then 1 end) "
                        + "from Job where project.id = :project")
                .setParameter("project", projectId)
                .setParameter("pending", JobState.PENDING)
                .setParameter("open", List.of(JobState.PENDING, JobState.RUNNING))
                .getSingleResult();
        return new Counts((long) row[0], (long) row[1]);
    }

    /** Lists all uncompleted (PENDING or RUNNING) jobs for a project. */
    public static List<Job> listOpenByProject(UUID projectId) {
        return list("project.id = ?1 and state in ?2", projectId, List.of(JobState.PENDING, JobState.RUNNING));
    }

    /** Lists all uncompleted jobs assigned to a worker. */
    public static List<Job> listOpenByWorker(UUID workerId) {
        return list("worker = ?1 and state in ?2", workerId, List.of(JobState.PENDING, JobState.RUNNING));
    }
}
