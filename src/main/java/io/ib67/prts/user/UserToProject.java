package io.ib67.prts.user;

import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.project.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Membership of a user in a project, carrying the permission that user holds there.
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

    /** Stored as the ordinal to match the int column — see {@link ProjectRole}. */
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "role", nullable = false)
    private ProjectRole projectRole;

    /**
     * Builds a membership with its composite key already filled in. {@code @MapsId} would only
     * populate it at flush time, which makes the entity awkward to compare or look up before then.
     */
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

    /** Memberships of a project, each with its user loaded for display. */
    public static List<UserToProject> listByProjectFetched(UUID projectId) {
        return find("from UserToProject l join fetch l.user where l.id.projectId = ?1", projectId).list();
    }

    /** Memberships of a user, each with its project loaded for display. */
    public static List<UserToProject> listByUserFetched(UUID userId) {
        return find("from UserToProject l join fetch l.project where l.id.userId = ?1", userId).list();
    }

    /** Whether the project is owned by anyone other than {@code excludedUserId}. */
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
