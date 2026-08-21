package io.ib67.prts.project;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
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
import org.hibernate.annotations.DynamicUpdate;
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
 * One run of a project.
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
// @DynamicUpdate: writers touching different columns must not revert each other's field.
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

    /**
     * What the job was scheduled against. Nullable because rows created before this column existed
     * have none; {@link io.ib67.prts.project.JobService#rerun(UUID, UUID)} needs it to schedule again.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_class")
    @ToString.Exclude
    private ResourceClass resourceClass;

    /**
     * Moves the job to {@code next} and keeps {@link #completedAt} aligned with the check
     * constraint: set on terminal states, cleared otherwise.
     */
    public void transitionTo(JobState next) {
        this.state = Objects.requireNonNull(next, "state");
        this.completedAt = next.isTerminal() ? Instant.now() : null;
    }

    public boolean isCompleted() {
        return state != null && state.isTerminal();
    }

    public static List<Job> listByProject(UUID projectId) {
        return list("project.id", projectId);
    }

    /** Jobs a worker still owes us an outcome for; used to fail them when it disconnects. */
    public static List<Job> listOpenByWorker(UUID workerId) {
        return list("worker = ?1 and state in ?2", workerId, List.of(JobState.PENDING, JobState.RUNNING));
    }

    /**
     * Loads the job together with the associations a re-schedule needs, so callers can read them
     * after the loading transaction has closed.
     */
    public static Optional<Job> findByIdFetched(UUID id) {
        return find("from Job j left join fetch j.resourceClass left join fetch j.project where j.id = ?1", id)
                .firstResultOptional();
    }
}
