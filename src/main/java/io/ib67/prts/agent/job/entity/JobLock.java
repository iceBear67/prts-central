package io.ib67.prts.agent.job.entity;

import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.Project;
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
 * Mutual exclusion lock for jobs scoped to a project.
 *
 * <p>Prevents concurrent execution of jobs requesting the same lock name within a project.
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

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Job job;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    /**
     * Attempts to acquire a lock for the given job.
     *
     * <p>Returns {@code true} if acquired, or {@code false} if already held by an active job.
     * Stale locks held by completed jobs are taken over.
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

    /**
     * Releases any lock held by the given job.
     */
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
