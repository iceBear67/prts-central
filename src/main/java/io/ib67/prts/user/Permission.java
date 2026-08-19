package io.ib67.prts.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "user_permission")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Permission extends PanacheEntityBase {
    @EmbeddedId
    protected Id id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    protected User user;

    public static Permission of(User user, String permission) {
        var granted = new Permission();
        granted.id = new Id(user.getId(), permission);
        granted.user = user;
        return granted;
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
    }
}
