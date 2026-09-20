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
 * Entity mapping a {@link WorkerVolume} mount into a {@link Task} at a specified container path.
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

    /** Lists the mounts of a volume with the mounting task fetched, ordered by task name. */
    public static List<TaskVolume> listByVolumeFetched(UUID volumeId) {
        return find("from TaskVolume m join fetch m.task t where m.id.volumeId = ?1 "
                + "order by t.name, t.id", volumeId).list();
    }

    /** Returns the number of tasks mounting the specified volume. */
    public static long countByVolume(UUID volumeId) {
        return count("id.volumeId", volumeId);
    }

    /** Deletes all volume mounts for the specified task. */
    public static long deleteByTask(UUID taskId) {
        return delete("id.taskId", taskId);
    }

    /**
     * Returns the worker hosting this task's volumes, or empty if no volumes are attached.
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
