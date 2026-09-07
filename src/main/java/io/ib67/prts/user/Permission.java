package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.ib67.prts.Reserved;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "user_permission")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Permission extends PanacheEntityBase {

    /** Sentinel project ID for global permissions. */
    public static final UUID GLOBAL = Reserved.ID;

    @EmbeddedId
    protected Id id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    protected User user;

    public static Permission of(User user, Perm perm, UUID projectId) {
        var granted = new Permission();
        granted.id = idOf(user.getId(), perm, projectId);
        granted.user = user;
        return granted;
    }

    /** Constructs a composite permission key for the user, permission, and project. */
    public static Id idOf(UUID userId, Perm perm, @Nullable UUID projectId) {
        return new Id(userId, perm.permission(), perm.global() ? GLOBAL : projectId);
    }

    /** Lists all user IDs who hold any permission within the given project. */
    public static List<UUID> listHolderIdsByProject(UUID projectId) {
        return getEntityManager()
                .createQuery("select distinct p.id.userId from Permission p where p.id.projectId = ?1",
                        UUID.class)
                .setParameter(1, projectId)
                .getResultList();
    }

    /** Deletes all permission grants within a project. */
    public static long deleteByProject(UUID projectId) {
        return delete("id.projectId", projectId);
    }

    public static long deleteByUserInProject(UUID userId, UUID projectId) {
        return delete("id.userId = ?1 and id.projectId = ?2", userId, projectId);
    }

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id {
        @Column(name = "user_id", nullable = false)
        protected UUID userId;
        @Column(name = "permission", nullable = false, columnDefinition = "varchar")
        protected String permission;
        @Column(name = "project_id", nullable = false)
        protected UUID projectId;
    }
}
