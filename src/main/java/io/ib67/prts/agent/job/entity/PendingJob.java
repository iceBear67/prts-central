package io.ib67.prts.agent.job.entity;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.ResourceClass;
import io.ib67.prts.project.Job;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A job that could not be dispatched because no worker was online. Rows are drained FIFO once a
 * suitable worker shows up or updates its remaining resources.
 */
@Entity
@Table(
        name = "pending_job",
        indexes = @Index(name = "idx_pending_job_created_at", columnList = "created_at")
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
    @JoinColumn(name = "resource_class", nullable = false)
    @ToString.Exclude
    private ResourceClass resourceClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", nullable = false, columnDefinition = "jsonb")
    private JobSpec spec;

    /**
     * The queued {@link Job}. Unique: a job is queued at most once. Nullable in DDL only so the
     * column can be added to a table that already has rows; dispatch skips rows without it.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Job job;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static List<PendingJob> listFifo() {
        return find("from PendingJob p join fetch p.resourceClass left join fetch p.job"
                + " order by p.createdAt, p.id").list();
    }

    public static void deleteByJob(UUID jobId) {
        delete("job.id", jobId);
    }
}
