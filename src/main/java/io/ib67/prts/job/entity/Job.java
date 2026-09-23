package io.ib67.prts.job.entity;

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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Represents a single job execution within a project.
 */
@Entity
@Table(
        name = "job",
        indexes = {
                @Index(name = "idx_job_project_id", columnList = "project_id"),
                @Index(name = "idx_job_task_id", columnList = "task_id")
        },
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

    /**
     * When a worker took the job, which is the only start the control plane observes.
     *
     * <p>Null while the job is still queued, and on one that was cancelled before placement —
     * {@link #createdAt} is when it was enqueued, so the two together separate queue time from run
     * time.
     */
    @Nullable
    @Column(name = "started_at")
    private Instant startedAt;

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
    @JoinColumn(name = "resource_class", referencedColumnName = "name", nullable = false)
    @ToString.Exclude
    private ResourceClass resourceClass;

    /** Template ID used to create this job, or {@code null} if not created from a template. */
    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    /**
     * ID of the task context for this job, or {@code null} if unassociated.
     * Stored without a foreign key constraint to preserve execution history after task closure.
     */
    @Nullable
    @Column(name = "task_id", updatable = false)
    private UUID taskId;

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
        return templateId == null
                ? null
                : new JobRequest(templateId, createOverride, resourceClass.getName(), taskId);
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
    /** {@link #VISIBLE_ROW} for the queries that alias the entity, which a join fetch requires. */
    private static final String VISIBLE_ALIASED = "(j.state <> :pending or j.worker is not null)";

    /**
     * Which visible jobs a listing asks for. Each field narrows it further; an unset one leaves that
     * dimension open.
     *
     * <p>One object rather than six positional arguments because no caller narrows on all of them —
     * the worker timeline passed three literal nulls to reach the two it wanted. And because the
     * listing and its total have to be narrowed identically or the page disagrees with the pager
     * drawn from it: handing the same filter to both is what makes that structural rather than a rule
     * every call site has to remember.
     *
     * @param projects the projects the caller may read, or null for every project ({@code admin:all})
     */
    @Builder
    public record Filter(
            @Nullable Collection<UUID> projects,
            @Nullable UUID project,
            @Nullable UUID task,
            @Nullable JobState state,
            @Nullable UUID worker,
            @Nullable Instant since) {
    }

    /** Lists one window of the matching visible jobs, newest first. */
    public static List<Job> listVisible(Filter filter, int offset, int length) {
        if (reachesNothing(filter)) {
            return List.of();
        }
        var parameters = new HashMap<String, Object>();
        return Job.<Job>find("from Job j join fetch j.project join fetch j.resourceClass where "
                        + where(filter, parameters) + " order by j.createdAt desc, j.id desc", parameters)
                .range(offset, offset + length - 1)
                .list();
    }

    /** What {@link #listVisible} would return unwindowed. */
    public static long countVisible(Filter filter) {
        if (reachesNothing(filter)) {
            return 0;
        }
        var parameters = new HashMap<String, Object>();
        return count("from Job j where " + where(filter, parameters), parameters);
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

    /** Counts visible jobs grouped by state, across every project or within one. */
    public static Map<JobState, Long> countByState(@Nullable UUID projectId) {
        var query = Job.getEntityManager()
                .createQuery("select j.state, count(j.id) from Job j where " + VISIBLE_ALIASED
                        + (projectId == null ? "" : " and j.project.id = :project")
                        + " group by j.state", Object[].class)
                .setParameter("pending", JobState.PENDING);
        if (projectId != null) {
            query.setParameter("project", projectId);
        }
        return query.getResultList().stream()
                .collect(Collectors.toMap(row -> (JobState) row[0], row -> (Long) row[1]));
    }

    /** Counts visible jobs grouped by project ID for a collection of projects. */
    public static Map<UUID, Long> countVisibleByProjects(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return Job.getEntityManager()
                .createQuery("select project.id, count(id) from Job "
                        + "where project.id in :projects and " + VISIBLE_ROW + " group by project.id",
                        Object[].class)
                .setParameter("projects", projectIds)
                .setParameter("pending", JobState.PENDING)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    // An empty project set matches nothing at all, which "in ()" cannot express.
    private static boolean reachesNothing(Filter filter) {
        return filter.projects() != null && filter.projects().isEmpty();
    }

    private static String where(Filter filter, Map<String, Object> parameters) {
        var query = new StringBuilder(VISIBLE_ALIASED);
        parameters.put("pending", JobState.PENDING);
        if (filter.projects() != null) {
            query.append(" and j.project.id in :projects");
            parameters.put("projects", filter.projects());
        }
        if (filter.project() != null) {
            query.append(" and j.project.id = :project");
            parameters.put("project", filter.project());
        }
        if (filter.task() != null) {
            query.append(" and j.taskId = :task");
            parameters.put("task", filter.task());
        }
        if (filter.state() != null) {
            query.append(" and j.state = :state");
            parameters.put("state", filter.state());
        }
        if (filter.worker() != null) {
            query.append(" and j.worker = :worker");
            parameters.put("worker", filter.worker());
        }
        if (filter.since() != null) {
            query.append(" and j.createdAt >= :since");
            parameters.put("since", filter.since());
        }
        return query.toString();
    }

    /** Lists all uncompleted (PENDING or RUNNING) jobs for a project. */
    public static List<Job> listOpenByProject(UUID projectId) {
        return list("project.id = ?1 and state in ?2", projectId, List.of(JobState.PENDING, JobState.RUNNING));
    }

    /** Lists all uncompleted jobs scoped to a task. */
    public static List<Job> listOpenByTask(UUID taskId) {
        return list("taskId = ?1 and state in ?2", taskId, List.of(JobState.PENDING, JobState.RUNNING));
    }

    /** Lists all uncompleted jobs assigned to a worker. */
    public static List<Job> listOpenByWorker(UUID workerId) {
        return list("worker = ?1 and state in ?2", workerId, List.of(JobState.PENDING, JobState.RUNNING));
    }

    /** Lists all uncompleted jobs assigned to any worker. */
    public static List<Job> listOpenAssigned() {
        return list("worker is not null and state in ?1", List.of(JobState.PENDING, JobState.RUNNING));
    }

    /** Returns the number of jobs referencing the specified resource class. */
    public static long countByResourceClass(String name) {
        return count("resourceClass.name = ?1", name);
    }

    /**
     * Detached descriptor of an active job used for external worker RPCs outside transaction boundaries.
     */
    public record Open(UUID id, @Nullable UUID worker) {
        public Open {
            Objects.requireNonNull(id, "id");
        }

        public static Open of(Job job) {
            return new Open(job.getId(), job.getWorker());
        }
    }
}
