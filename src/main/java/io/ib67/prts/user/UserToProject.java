package io.ib67.prts.user;

import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.project.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
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
    @ToString.Exclude
    private User user;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
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
