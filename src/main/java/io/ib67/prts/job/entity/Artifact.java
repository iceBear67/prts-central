package io.ib67.prts.job.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Metadata record for a file produced by a job. The actual content is stored in object storage.
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

    /** When the upload was promoted to a row, which is the only upload time the control plane observes. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Job job;

    public static List<Artifact> listByJob(UUID jobId) {
        return list("job.id", jobId);
    }

    /** Finds all artifacts for a batch of jobs. */
    public static List<Artifact> listByJobs(Collection<UUID> jobIds) {
        return jobIds.isEmpty() ? List.of() : list("job.id in ?1", jobIds);
    }

    /** Retrieves all artifact storage keys for a project. */
    public static List<String> listObjectKeysByProject(UUID projectId) {
        return getEntityManager()
                .createQuery("select a.objectKey from Artifact a where a.job.project.id = ?1", String.class)
                .setParameter(1, projectId)
                .getResultList();
    }

    /** Finds an artifact by ID within a specific project. */
    public static Optional<Artifact> findInProject(UUID projectId, UUID artifactId) {
        return find("id = ?1 and job.project.id = ?2", artifactId, projectId).firstResultOptional();
    }

    /**
     * Lists one page of artifacts across every project, newest first, optionally narrowed to a project
     * or a single job. Fetches the producing job and its project, which a cross-project view has to name.
     */
    public static List<Artifact> search(
            @Nullable UUID projectId, @Nullable UUID jobId, int offset, int length) {
        var parameters = new HashMap<String, Object>();
        return find("from Artifact a join fetch a.job j join fetch j.project where "
                        + where(projectId, jobId, parameters)
                        + " order by a.createdAt desc, a.id desc", parameters)
                .range(offset, offset + length - 1)
                .list();
    }

    public static long countSearch(@Nullable UUID projectId, @Nullable UUID jobId) {
        var parameters = new HashMap<String, Object>();
        return count("from Artifact a join a.job j where " + where(projectId, jobId, parameters),
                parameters);
    }

    private static String where(
            @Nullable UUID projectId, @Nullable UUID jobId, Map<String, Object> parameters) {
        var where = new StringBuilder("1 = 1");
        if (projectId != null) {
            where.append(" and j.project.id = :project");
            parameters.put("project", projectId);
        }
        if (jobId != null) {
            where.append(" and j.id = :job");
            parameters.put("job", jobId);
        }
        return where.toString();
    }
}
