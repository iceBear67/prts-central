package io.ib67.prts.agent.worker.entity;

import io.ib67.prts.job.entity.Project;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Collection;
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

    /** Lists volumes hosted on the given worker, fetching owning projects. */
    public static List<WorkerVolume> listByWorker(UUID workerId) {
        return find("from WorkerVolume v join fetch v.project where v.worker.id = ?1", workerId).list();
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

    /** Lists a project's volumes, fetching their host workers and owning project. */
    public static List<WorkerVolume> listByProject(UUID projectId) {
        return find("from WorkerVolume v join fetch v.worker join fetch v.project "
                + "where v.project.id = ?1 order by v.name", projectId).list();
    }

    /** Loads a volume with its project and host worker fetched, for reading outside a transaction. */
    public static Optional<WorkerVolume> findByIdFetched(UUID volumeId) {
        return find("from WorkerVolume v join fetch v.worker join fetch v.project where v.id = ?1", volumeId)
                .firstResultOptional();
    }

    public static Optional<WorkerVolume> findInProject(UUID projectId, UUID volumeId) {
        return WorkerVolume.<WorkerVolume>findByIdOptional(volumeId)
                .filter(volume -> volume.getProject().getId().equals(projectId));
    }
}
