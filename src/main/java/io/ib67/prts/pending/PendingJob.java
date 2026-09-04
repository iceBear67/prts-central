package io.ib67.prts.pending;

import io.ib67.prts.project.JobRequest;
import io.ib67.prts.project.Project;
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
 * A create request that was authorized and kept, waiting for a worker that can take it. It stores the
 * request rather than a job or a spec: the dispatcher replays it through the same path the endpoint
 * uses, so the merged spec and the project's secrets are produced per attempt and never live here.
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

    /**
     * Whose authorization the entry is holding. Kept like {@code Job.worker}, by id and without a FK.
     * Never absent: {@link PendingJobService#enqueue} refuses a caller it cannot name, so a future
     * path that queues without a requester fails there rather than storing a blank one here.
     */
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    /**
     * The request as {@link io.ib67.prts.project.JobLauncher#authorize} handed it back, its resource
     * class pinned — by name only: the two-column reference belongs on what actually runs, and
     * replaying the name lets {@code ResourceClass.findVisible} apply its shadowing rule again.
     */
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

    /** When the next attempt may run; the backoff that keeps an empty fleet from being retried per tick. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** Why the last attempt did not dispatch, for a requester who is no longer on the line. */
    @Column(name = "last_error", columnDefinition = "varchar")
    private String lastError;

    /** The job the entry became, once one was created and taken. */
    @Column(name = "job_id")
    private UUID jobId;

    /**
     * Entries an attempt may be made for, oldest first. Locked because the claim that follows is a
     * check-then-write against a cancel.
     */
    public static List<PendingJob> listDue(Instant now, int limit) {
        return PendingJob.<PendingJob>find(
                        "state = ?1 and nextAttemptAt <= ?2 order by createdAt, id",
                        PendingJobState.QUEUED, now)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .page(0, limit)
                .list();
    }

    public static List<PendingJob> listByProject(UUID projectId) {
        return list("project.id = ?1 order by createdAt desc", projectId);
    }

    /** What the per-project cap counts: entries that still stand to become a job. */
    public static long countActive(UUID projectId) {
        return count("project.id = ?1 and state in ?2", projectId,
                List.of(PendingJobState.QUEUED, PendingJobState.DISPATCHING));
    }

    public static int expireOverdue(Instant now) {
        return update("state = ?1 where state = ?2 and expiresAt < ?3",
                PendingJobState.EXPIRED, PendingJobState.QUEUED, now);
    }

    /**
     * Nothing survives a restart in flight — the worker map is in memory — so a claim left behind by
     * a previous run is owed another attempt rather than being stuck.
     */
    public static int resetDispatching() {
        return update("state = ?1 where state = ?2",
                PendingJobState.QUEUED, PendingJobState.DISPATCHING);
    }
}
