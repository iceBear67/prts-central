package io.ib67.prts.user;

import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.project.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
