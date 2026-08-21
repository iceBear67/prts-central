package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

@Entity
@Table(name = "user_permission")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Permission extends PanacheEntityBase {

    /**
     * Scope of a {@link Perm#global()} grant. The project is part of the primary key and Postgres
     * cannot key on null, so "no project" is this reserved all-zero id rather than null.
     */
    public static final UUID GLOBAL = new UUID(0, 0);

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

    /**
     * @param projectId ignored for a global permission, and the returned id carries no project for a
     *                  project-scoped one when it is null — such an id matches no row.
     */
    public static Id idOf(UUID userId, Perm perm, @Nullable UUID projectId) {
        return new Id(userId, perm.permission(), perm.global() ? GLOBAL : projectId);
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
