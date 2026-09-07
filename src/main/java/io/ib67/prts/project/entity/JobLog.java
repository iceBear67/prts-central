package io.ib67.prts.project.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single log entry emitted during a job execution.
 */
@Entity
@Table(name = "job_log", indexes = @Index(name = "idx_job_log", columnList = "job_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class JobLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Job job;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "topic", columnDefinition = "varchar")
    private String topic;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    /** Whether this log line represents an error. */
    @Column(name = "error", nullable = false)
    private Boolean error;

    /** Sorts chronologically, using log ID to break timestamp ties. */
    private static Sort chronological() {
        return Sort.by("createdAt").and("id");
    }

    /** Retrieves a paginated slice of logs for a job. */
    public static List<JobLog> listByJob(UUID jobId, int offset, int length) {
        return find("job.id", chronological(), jobId).range(offset, offset + length - 1).list();
    }
}
