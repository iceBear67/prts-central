package io.ib67.prts.job.task.entity;

import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mounts a {@link WorkerVolume} into a {@link Task}.
 *
 * <p>The relation is many-to-many: one volume may be shared by several tasks, and each of them mounts
 * it wherever it likes — which is why {@code mountPoint} lives on this row rather than on the volume.
 *
 * <p>Closing a task drops its mounts and nothing else. Volumes outlive the tasks that used them and are
 * only removed from the worker by an explicit delete.
 */
@Entity
@Table(name = "task_volume")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class TaskVolume extends PanacheEntityBase {

    @EmbeddedId
    private Id id;

    @MapsId("taskId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Task task;

    @MapsId("volumeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "volume_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private WorkerVolume volume;

    /** Path the task's jobs mount this volume at. */
    @Column(name = "mount_point", nullable = false, columnDefinition = "varchar")
    private String mountPoint;

    public static TaskVolume of(Task task, WorkerVolume volume, String mountPoint) {
        var mount = new TaskVolume();
        mount.id = new Id(task.getId(), volume.getId());
        mount.task = task;
        mount.volume = volume;
        mount.mountPoint = mountPoint;
        return mount;
    }

    /** Lists a task's mounts with the volume and its host worker fetched. */
    public static List<TaskVolume> listByTaskFetched(UUID taskId) {
        return find("from TaskVolume m join fetch m.volume v join fetch v.worker "
                + "where m.id.taskId = ?1 order by m.mountPoint", taskId).list();
    }

    public static Optional<TaskVolume> findMount(UUID taskId, UUID volumeId) {
        return findByIdOptional(new Id(taskId, volumeId));
    }

    /** How many tasks currently mount this volume. Guards deletion of a volume still in use. */
    public static long countByVolume(UUID volumeId) {
        return count("id.volumeId", volumeId);
    }

    /** Drops every mount of a task. The volumes themselves are untouched. */
    public static long deleteByTask(UUID taskId) {
        return delete("id.taskId", taskId);
    }

    /**
     * The worker hosting this task's volumes, or empty if it mounts none.
     *
     * <p>A job may only be placed on a worker holding all of its volumes, so a task with mounts is
     * effectively pinned to one. Attaching enforces this rather than storing it.
     */
    public static Optional<UUID> workerOf(UUID taskId) {
        return TaskVolume.<TaskVolume>find(
                        "from TaskVolume m join fetch m.volume v join fetch v.worker where m.id.taskId = ?1", taskId)
                .firstResultOptional()
                .map(mount -> mount.getVolume().getWorker().getId());
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class Id {

        @Column(name = "task_id", nullable = false)
        private UUID taskId;

        @Column(name = "volume_id", nullable = false)
        private UUID volumeId;
    }
}
