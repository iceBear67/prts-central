package io.ib67.prts.agent.worker.entity;

import io.ib67.prts.job.entity.Project;
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
import java.util.UUID;

/**
 * One project's standing permission to run in one {@link ResourceClass}.
 *
 * <p>Only a class that is not {@link ResourceClass#isShared() shared} needs a row here: a shared
 * class is every project's to use, and this table names the projects a restricted one is open to.
 * Both sides cascade at the database level — dropping a project or a class drops its rows, which is
 * why neither deletion path has to know this table exists.
 */
@Entity
@Table(name = "project_resource_class")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ProjectResourceClass extends PanacheEntityBase {

    @EmbeddedId
    private Id id;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    @MapsId("resourceClass")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_class", referencedColumnName = "name",
            nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private ResourceClass resourceClass;

    public static ProjectResourceClass of(Project project, ResourceClass resourceClass) {
        var grant = new ProjectResourceClass();
        grant.id = new Id(project.getId(), resourceClass.getName());
        grant.project = project;
        grant.resourceClass = resourceClass;
        return grant;
    }

    /** Whether the project is named on this class. */
    public static boolean granted(UUID projectId, String resourceClass) {
        return count("id.projectId = ?1 and id.resourceClass = ?2", projectId, resourceClass) > 0;
    }

    /** Lists one page of a class's grants with the project fetched, ordered by project name. */
    public static List<ProjectResourceClass> listByResourceClassFetched(
            String resourceClass, int offset, int length) {
        return find("from ProjectResourceClass g join fetch g.project p "
                + "where g.id.resourceClass = ?1 order by p.name, p.id", resourceClass)
                .range(offset, offset + length - 1)
                .list();
    }

    public static long countByResourceClass(String resourceClass) {
        return count("id.resourceClass", resourceClass);
    }

    /** Withdraws the grant. Returns whether there was one. */
    public static boolean revoke(UUID projectId, String resourceClass) {
        return delete("id.projectId = ?1 and id.resourceClass = ?2", projectId, resourceClass) > 0;
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

        @Column(name = "resource_class", nullable = false, columnDefinition = "varchar")
        private String resourceClass;
    }
}
