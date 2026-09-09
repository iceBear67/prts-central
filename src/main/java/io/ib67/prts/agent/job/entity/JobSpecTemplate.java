package io.ib67.prts.agent.job.entity;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.job.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reusable {@link JobSpec} template.
 *
 * <p>Can be scoped to a project or defined globally (when project is null).
 */
@Entity
@Table(
        name = "job_spec_template",
        indexes = @Index(name = "idx_job_spec_template_project_id", columnList = "project_id"))
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
    @JoinColumns({
            @JoinColumn(name = "resource_class", referencedColumnName = "name"),
            @JoinColumn(name = "resource_class_project", referencedColumnName = "project_id")
    })
    @ToString.Exclude
    private ResourceClass resourceClass;

    /** Owning project, or null if this template is global. */
    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    private static final String VISIBLE_TO =
            "from JobSpecTemplate t left join fetch t.resourceClass "
                    + "where (t.project is null or t.project.id = ?1)";

    /** Lists templates visible to the given project (including global templates). */
    public static List<JobSpecTemplate> listVisibleFetched(UUID projectId) {
        return find(VISIBLE_TO, projectId).list();
    }

    public static Optional<JobSpecTemplate> findVisibleFetched(UUID projectId, UUID id) {
        return find(VISIBLE_TO + " and t.id = ?2", projectId, id).firstResultOptional();
    }

    private static final String GLOBAL =
            "from JobSpecTemplate t left join fetch t.resourceClass where t.project is null";

    /** Lists all global templates. */
    public static List<JobSpecTemplate> listGlobalFetched() {
        return find(GLOBAL).list();
    }

    public static Optional<JobSpecTemplate> findGlobalFetched(UUID id) {
        return find(GLOBAL + " and t.id = ?1", id).firstResultOptional();
    }
}
