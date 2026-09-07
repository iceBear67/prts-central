package io.ib67.prts.pending;

import io.ib67.prts.project.entity.JobRequest;
import io.ib67.prts.project.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
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
 * Queued job request waiting to be dispatched to an available worker.
 */
@Entity
@Table(
        name = "pending_job",
        indexes = {
                @Index(name = "idx_pending_job_project_id", columnList = "project_id"),
                @Index(name = "idx_pending_job_due", columnList = "state, next_attempt_at")
        },
        check = @CheckConstraint(
                name = "pending_job_state_values",
                constraint = "state IN ('QUEUED', 'DISPATCHING', 'DISPATCHED', 'CANCELLED', "
                        + "'EXPIRED', 'FAILED')"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PendingJob extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    /** User ID who requested the job. */
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    /** Authorized job request payload. */
    @Embedded
    @AttributeOverride(name = "resourceClass",
            column = @Column(name = "resource_class", nullable = false, updatable = false, columnDefinition = "varchar"))
    @ToString.Exclude
    private JobRequest request;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, columnDefinition = "varchar")
    private PendingJobState state = PendingJobState.QUEUED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Earliest time the next dispatch attempt may run. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** Error message from the most recent failed dispatch attempt. */
    @Column(name = "last_error", columnDefinition = "varchar")
    private String lastError;

    /** The created Job ID once dispatched. */
    @Column(name = "job_id")
    private UUID jobId;

    /** Retrieves due queued jobs, ordered by creation time. */
    public static List<PendingJob> listDue(Instant now, int limit) {
        return PendingJob.<PendingJob>find(
                        "state = ?1 and nextAttemptAt <= ?2 order by createdAt, id",
                        PendingJobState.QUEUED, now)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .page(0, limit)
                .list();
    }

    /** Lists undispatched pending jobs for a project. */
    public static List<PendingJob> listUnplacedByProject(UUID projectId, int limit) {
        return PendingJob.<PendingJob>find("project.id = ?1 and jobId is null order by createdAt desc, id desc", projectId)
                .page(0, limit)
                .list();
    }

    /** Counts pending jobs that are currently queued or dispatching for a project. */
    public static long countActive(UUID projectId) {
        return count("project.id = ?1 and state in ?2", projectId,
                List.of(PendingJobState.QUEUED, PendingJobState.DISPATCHING));
    }

    /** Cancels all active pending jobs for a project. */
    public static int cancelActive(UUID projectId) {
        return update("state = ?1 where project.id = ?2 and state in ?3",
                PendingJobState.CANCELLED, projectId,
                List.of(PendingJobState.QUEUED, PendingJobState.DISPATCHING));
    }

    public static int expireOverdue(Instant now) {
        return update("state = ?1 where state = ?2 and expiresAt < ?3",
                PendingJobState.EXPIRED, PendingJobState.QUEUED, now);
    }

    /** Resets any jobs left in DISPATCHING state back to QUEUED after server restart. */
    public static int resetDispatching() {
        return update("state = ?1 where state = ?2",
                PendingJobState.QUEUED, PendingJobState.DISPATCHING);
    }
}
