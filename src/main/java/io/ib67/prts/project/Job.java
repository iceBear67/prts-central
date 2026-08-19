package io.ib67.prts.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One run of a project.
 */
@Entity
@Table(
        name = "job",
        indexes = @Index(name = "idx_job_project_id", columnList = "project_id"),
        check = @CheckConstraint(
                name = "job_completion_consistency",
                constraint = "(completed_at IS NULL AND success IS NULL) "
                        + "OR (completed_at IS NOT NULL AND success IS NOT NULL)"
        )
)
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

    /** Null while the job is still running. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Tristate: null while running, {@code TRUE} on success, {@code FALSE} on failure. */
    @Column(name = "success")
    private Boolean success;

    private UUID runner;

    /**
     * Finishes the job. Both columns are written together because the check constraint rejects a
     * row that has only one of them set.
     */
    public void complete(boolean succeeded) {
        this.completedAt = Instant.now();
        this.success = succeeded;
    }

    public boolean isCompleted() {
        return completedAt != null;
    }

    public static List<Job> listByProject(UUID projectId) {
        return list("project.id", projectId);
    }
}
