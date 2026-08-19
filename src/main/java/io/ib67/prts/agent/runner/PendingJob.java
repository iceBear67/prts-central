package io.ib67.prts.agent.runner;

import io.ib67.prts.agent.job.JobSpec;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A job that could not be dispatched because no runner was online. Rows are drained FIFO once a
 * suitable runner shows up or updates its remaining resources.
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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static List<PendingJob> listFifo() {
        return listAll(Sort.by("createdAt").and("id"));
    }
}
