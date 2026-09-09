package io.ib67.prts.user;

import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.job.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Represents the membership and role of a user within a project.
 */
@Entity
@Table(name = "user_to_project")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class UserToProject extends PanacheEntityBase {

    @EmbeddedId
    private Id id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private User user;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "role", nullable = false)
    private ProjectRole projectRole;

    public static UserToProject of(User user, Project project, ProjectRole projectRole) {
        var link = new UserToProject();
        link.id = new Id(user.getId(), project.getId());
        link.user = user;
        link.project = project;
        link.projectRole = projectRole;
        return link;
    }

    public static Optional<UserToProject> findByUserAndProject(UUID userId, UUID projectId) {
        return findByIdOptional(new Id(userId, projectId));
    }

    /** Lists memberships for a project with users eagerly fetched. */
    public static List<UserToProject> listByProjectFetched(UUID projectId) {
        return find("from UserToProject l join fetch l.user where l.id.projectId = ?1", projectId).list();
    }

    /** Lists memberships for a user with projects eagerly fetched. */
    public static List<UserToProject> listByUserFetched(UUID userId) {
        return find("from UserToProject l join fetch l.project where l.id.userId = ?1", userId).list();
    }

    /** Counts members grouped by project ID for a collection of projects. */
    public static Map<UUID, Long> countByProjects(Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return getEntityManager()
                .createQuery("select l.id.projectId, count(l) from UserToProject l "
                        + "where l.id.projectId in ?1 group by l.id.projectId", Object[].class)
                .setParameter(1, projectIds)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    /** Checks if another owner exists for the project. */
    public static boolean hasOtherOwner(UUID projectId, UUID excludedUserId) {
        return count("id.projectId = ?1 and projectRole = ?2 and id.userId <> ?3",
                projectId, ProjectRole.OWNER, excludedUserId) > 0;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class Id {

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "project_id", nullable = false)
        private UUID projectId;
    }

}
