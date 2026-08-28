package io.ib67.prts.auth;

import io.ib67.prts.user.User;
import io.ib67.prts.user.UserToProject;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.util.Optional;

/**
 * An external login linked to a {@link User}. Several identities may point at the same user.
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

    /**
     * Plain foreign key, not part of the key: unlike {@link UserToProject} the primary key here is
     * (issuer, subject), so there is nothing for {@code @MapsId} to derive.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    public static OAuthIdentity of(String issuer, String subject, User user) {
        var identity = new OAuthIdentity();
        identity.id = new Id(issuer, subject);
        identity.user = user;
        return identity;
    }

    /** Selects the user itself, so the caller can use it outside a session. */
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
