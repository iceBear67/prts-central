package io.ib67.prts.agent.job.entity;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.project.entity.JobState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.UUID;

/**
 * The holder of a {@link JobSpec#lock()}, so that two jobs naming the same lock never run at once.
 * Held from the moment a job is handed to a worker until it reaches a terminal
 * {@link JobState}; a job that cannot take the lock is refused, not queued.
 *
 * <p>Locks are scoped to a project: the same name in two projects is two independent locks.
 */
@Entity
@Table(name = "job_lock")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class JobLock extends PanacheEntityBase {

    @EmbeddedId
    private Id id;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    /** Unique: a spec carries a single lock, so a job holds at most one. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Job job;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    /**
     * Takes {@code name} for {@code jobId} within that job's project. {@code false} means another
     * live job holds it, so the caller has to refuse this one.
     *
     * <p>A row whose holder is already terminal (or gone) is taken over, so a lock cannot be
     * orphaned by a crash between dispatch and completion. Must run inside a transaction; the
     * insert can lose a race on the primary key, which the caller treats as "busy".
     */
    public static boolean tryAcquire(String name, UUID jobId) {
        var job = Job.<Job>findById(jobId);
        if (job == null) {
            return false;
        }
        var key = new Id(job.getProject().getId(), name);
        var existing = JobLock.<JobLock>findById(key, LockModeType.PESSIMISTIC_WRITE);
        if (existing != null) {
            var holderId = existing.getJob().getId();
            if (jobId.equals(holderId)) {
                return true;
            }
            var holder = Job.<Job>findById(holderId);
            if (holder != null && !holder.isCompleted()) {
                return false;
            }
            existing.setJob(job);
            existing.setAcquiredAt(Instant.now());
            return true;
        }
        var lock = new JobLock();
        lock.id = key;
        lock.project = job.getProject();
        lock.job = job;
        lock.acquiredAt = Instant.now();
        lock.persistAndFlush();
        return true;
    }

    /** Idempotent: releases whatever lock {@code jobId} holds, if any. Must run in a transaction. */
    public static void releaseBy(UUID jobId) {
        delete("job.id", jobId);
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class Id {

        @Column(name = "project_id", nullable = false)
        private UUID projectId;

        @Column(name = "name", nullable = false, columnDefinition = "varchar")
        private String name;
    }
}
