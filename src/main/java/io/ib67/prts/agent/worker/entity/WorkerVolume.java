package io.ib67.prts.agent.worker.entity;

import io.ib67.prts.project.entity.Project;
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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A volume attached to a {@link Worker}. {@link io.ib67.prts.agent.job.JobSpec#volumes()} keys are these ids.
 */
@Entity
@Table(
        name = "worker_volume",
        indexes = {
                @Index(name = "idx_worker_volume_worker_id", columnList = "worker_id"),
                @Index(name = "idx_worker_volume_project_id", columnList = "project_id")
        },
        check = @CheckConstraint(
                name = "worker_volume_usage",
                constraint = "length >= 0 AND used >= 0 AND used <= length"
        )
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
    @ToString.Exclude
    private Project project;

    @Column(name = "length", nullable = false)
    private long length;

    @Column(name = "used", nullable = false)
    private long used;

    public long remaining() {
        return length - used;
    }

    public static List<WorkerVolume> listByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return find("from WorkerVolume v join fetch v.worker join fetch v.project where v.id in ?1", ids).list();
    }
}
