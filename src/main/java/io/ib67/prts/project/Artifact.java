package io.ib67.prts.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
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
import org.hibernate.annotations.UuidGenerator;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A file produced by a job. Only the object store key and size are kept here; the bytes live in the
 * object store.
 */
@Entity
@Table(name = "artifact", indexes = @Index(name = "idx_artifact_job_id", columnList = "job_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Artifact extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    @Column(name = "object_key", nullable = false, columnDefinition = "text")
    private String objectKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    @ToString.Exclude
    private Job job;

    public static List<Artifact> listByJob(UUID jobId) {
        return list("job.id", jobId);
    }

    /** Same rule as {@code JobService.findInProject}: found by its own id, then kept only if it belongs to the project. */
    public static Optional<Artifact> findInProject(UUID projectId, UUID artifactId) {
        return find("id = ?1 and job.project.id = ?2", artifactId, projectId).firstResultOptional();
    }
}
