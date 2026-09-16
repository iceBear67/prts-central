package io.ib67.prts.agent.worker.entity;

import io.ib67.prts.dto.StorageUsage;
import io.ib67.prts.job.entity.Project;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Storage volume hosted on a worker and allocated to a project.
 */
@Entity
@Table(
        name = "worker_volume",
        indexes = {
                @Index(name = "idx_worker_volume_worker_id", columnList = "worker_id"),
                @Index(name = "idx_worker_volume_project_id", columnList = "project_id")
        },
        check = {
                @CheckConstraint(
                        name = "worker_volume_usage",
                        constraint = "length >= 0 AND used >= 0 AND used <= length"
                ),
                @CheckConstraint(
                        name = "worker_volume_state_values",
                        constraint = "state IN ('PROVISIONING', 'READY', 'RELEASING')"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class WorkerVolume extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    @ToString.Exclude
    private Worker worker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    @Column(name = "length", nullable = false)
    private long length;

    @Column(name = "used", nullable = false)
    private long used;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, columnDefinition = "varchar")
    private VolumeState state = VolumeState.PROVISIONING;

    public long remaining() {
        return length - used;
    }

    public static long countByWorker(UUID workerId) {
        return count("worker.id", workerId);
    }

    /** Volume counts grouped by worker ID. Workers hosting none are absent from the map. */
    public static Map<UUID, Long> countByWorkers(Collection<UUID> workerIds) {
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        return getEntityManager()
                .createQuery("select v.worker.id, count(v.id) from WorkerVolume v "
                        + "where v.worker.id in ?1 group by v.worker.id", Object[].class)
                .setParameter(1, workerIds)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    public static List<WorkerVolume> listByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("from WorkerVolume v join fetch v.worker join fetch v.project where v.id in ?1", ids).list();
    }

    /**
     * Lists all volumes belonging to a project, eagerly fetching worker and project associations.
     */
    public static List<WorkerVolume> listByProject(UUID projectId) {
        return find("from WorkerVolume v join fetch v.worker join fetch v.project "
                + "where v.project.id = ?1 order by v.name", projectId).list();
    }

    /** Finds a volume by ID, eagerly fetching worker and project associations. */
    public static Optional<WorkerVolume> findByIdFetched(UUID volumeId) {
        return find("from WorkerVolume v join fetch v.worker join fetch v.project where v.id = ?1", volumeId)
                .firstResultOptional();
    }

    /** Finds a volume within a project, eagerly fetching worker and project associations. */
    public static Optional<WorkerVolume> findInProject(UUID projectId, UUID volumeId) {
        return find("from WorkerVolume v join fetch v.worker join fetch v.project "
                + "where v.id = ?1 and v.project.id = ?2", volumeId, projectId).firstResultOptional();
    }

    /**
     * Lists one page of volumes across every worker and project, narrowed by whichever of the host,
     * the owning project, the state and the name fragment is given.
     */
    public static List<WorkerVolume> search(
            @Nullable UUID workerId, @Nullable UUID projectId, @Nullable VolumeState state,
            @Nullable String query, int offset, int length) {
        var parameters = new HashMap<String, Object>();
        var where = where(workerId, projectId, state, query, parameters);
        return find("from WorkerVolume v join fetch v.worker join fetch v.project where " + where
                        + " order by v.name, v.id", parameters)
                .range(offset, offset + length - 1)
                .list();
    }

    public static long countSearch(
            @Nullable UUID workerId, @Nullable UUID projectId, @Nullable VolumeState state,
            @Nullable String query) {
        var parameters = new HashMap<String, Object>();
        return count("from WorkerVolume v where "
                + where(workerId, projectId, state, query, parameters), parameters);
    }

    private static String where(
            @Nullable UUID workerId, @Nullable UUID projectId, @Nullable VolumeState state,
            @Nullable String query, Map<String, Object> parameters) {
        var where = new StringBuilder("1 = 1");
        if (workerId != null) {
            where.append(" and v.worker.id = :worker");
            parameters.put("worker", workerId);
        }
        if (projectId != null) {
            where.append(" and v.project.id = :project");
            parameters.put("project", projectId);
        }
        if (state != null) {
            where.append(" and v.state = :state");
            parameters.put("state", state);
        }
        if (query != null && !query.isBlank()) {
            where.append(" and lower(v.name) like :name");
            parameters.put("name", "%" + query.strip().toLowerCase() + "%");
        }
        return where.toString();
    }

    /** Drops every volume row hosted on a worker, for a host that is never coming back. */
    public static long deleteByWorker(UUID workerId) {
        return delete("worker.id", workerId);
    }

    /**
     * Volume count and allocated bytes, across every worker or within one project. Allocated, not
     * consumed: nothing writes {@link #used} yet (see TODO.md).
     */
    public static StorageUsage allocated(@Nullable UUID projectId) {
        // sum() returns null when no rows exist, whereas count() returns 0.
        var query = getEntityManager().createQuery("select count(v), sum(v.length) from WorkerVolume v"
                + (projectId == null ? "" : " where v.project.id = :project"));
        if (projectId != null) {
            query.setParameter("project", projectId);
        }
        var row = (Object[]) query.getSingleResult();
        var bytes = (Long) row[1];
        return new StorageUsage((long) row[0], bytes == null ? 0 : bytes);
    }
}
