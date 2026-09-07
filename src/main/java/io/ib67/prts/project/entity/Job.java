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
     * What the job runs under, resolved at create time from the request or, failing that, the
     * template. Eager because every view of a job publishes its name and callers map jobs after
     * their transaction closed; the row is a tiny immutable lookup and the join is inner.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumns({
            @JoinColumn(name = "resource_class", referencedColumnName = "name", nullable = false),
            @JoinColumn(name = "resource_class_project", referencedColumnName = "project_id",
                    nullable = false)
    })
    @ToString.Exclude
    private ResourceClass resourceClass;

    /**
     * The template the job was made from, with {@link #createOverride} the part of the create request
     * that is not recoverable from the job itself — see {@link #toRequest()}. Null means some other
     * path made the job and there is nothing to replay.
     */
    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    /**
     * Who asked for the job, carried over from the queue entry that produced it — the dispatcher
     * thread has no requester of its own, so not passing it here loses it for good. Kept by id
     * without a FK, like {@link #worker}. Deliberately not part of {@link JobRequest}: a re-run is
     * requested by whoever posts it back, and putting this in the request would let them say otherwise.
     */
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "create_override", updatable = false, columnDefinition = "jsonb")
    @ToString.Exclude
    private JobSpecOverride createOverride;

    /**
     * The request that reproduces this job, for the client to post back to {@code POST .../job} — how a
     * re-run goes through the same gates as the original. The resource class is the one that actually
     * ran, not what the requester typed: naming it pins the re-run to it even if the template has moved
     * on since. {@code null} when there is nothing to replay. Reads only what a detached job carries.
     */
    @Nullable
    public JobRequest toRequest() {
        return templateId == null ? null : new JobRequest(templateId, createOverride, resourceClass.getName());
    }

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

    /**
     * A {@code PENDING} job with no worker is an attempt still deciding where to put it — or one a
     * crash left behind, see TODO.md — and either way not something to show a reader yet: the queue
     * entry is what stands for it until {@code claimJob} hands it over.
     */
    private static final String VISIBLE_ROW = "(state <> :pending or worker is not null)";
    private static final String VISIBLE = "project.id = :project and " + VISIBLE_ROW;

    /** Newest first. */
    public static List<Job> listVisibleByProject(UUID projectId, int limit) {
        return find(VISIBLE + " order by createdAt desc, id desc",
                Map.of("project", projectId, "pending", JobState.PENDING))
                .page(0, limit)
                .list();
    }

    /**
     * Both counts a project view shows, off one pass: they walk the same rows of the same index, so
     * two queries would read every one of them twice.
     *
     * @param visible jobs {@link #listVisibleByProject} would list.
     * @param running of those, the ones not yet done: handed to a worker, or running on one.
     */
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

    /** Jobs still to be stopped before the project can go: not yet placed, or running somewhere. */
    public static List<Job> listOpenByProject(UUID projectId) {
        return list("project.id = ?1 and state in ?2", projectId, List.of(JobState.PENDING, JobState.RUNNING));
    }

    /** Jobs a worker still owes us an outcome for; used to fail them when it disconnects. */
    public static List<Job> listOpenByWorker(UUID workerId) {
        return list("worker = ?1 and state in ?2", workerId, List.of(JobState.PENDING, JobState.RUNNING));
    }
}
