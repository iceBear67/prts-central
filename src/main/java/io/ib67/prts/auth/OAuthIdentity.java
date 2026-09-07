package io.ib67.prts.auth;

import io.ib67.prts.user.User;
import io.ib67.prts.user.UserToProject;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.Optional;

/**
 * External OAuth/OIDC identity linked to a {@link User}.
 */
@Entity
@Table(name = "oauth_identity")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class OAuthIdentity extends PanacheEntityBase {

    @EmbeddedId
    private Id id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private User user;

    public static OAuthIdentity of(String issuer, String subject, User user) {
        var identity = new OAuthIdentity();
        identity.id = new Id(issuer, subject);
        identity.user = user;
        return identity;
    }

    public static Optional<User> findUser(String issuer, String subject) {
        return getEntityManager().createQuery(
                        "select i.user from OAuthIdentity i where i.id.issuer = ?1 and i.id.subject = ?2",
                        User.class)
                .setParameter(1, issuer)
                .setParameter(2, subject)
                .getResultStream()
                .findFirst();
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class Id {

        @Column(name = "issuer", nullable = false, columnDefinition = "varchar")
        private String issuer;

        @Column(name = "subject", nullable = false, columnDefinition = "varchar")
        private String subject;
    }

}
