package io.ib67.prts.agent.job.entity;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.ResourceClass;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A reusable {@link JobSpec} that can be looked up and scheduled later. The resource class is
 * optional; when null, the caller chooses one at schedule time.
 */
@Entity
@Table(name = "job_spec_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class JobSpecTemplate extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", nullable = false, columnDefinition = "jsonb")
    private JobSpec spec;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_class")
    @ToString.Exclude
    private ResourceClass resourceClass;

    public static List<JobSpecTemplate> listAllFetched() {
        return find("from JobSpecTemplate t left join fetch t.resourceClass").list();
    }

    public static Optional<JobSpecTemplate> findByIdFetched(UUID id) {
        return find("from JobSpecTemplate t left join fetch t.resourceClass where t.id = ?1", id)
                .firstResultOptional();
    }
}
