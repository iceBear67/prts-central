package io.ib67.prts.auth;

import io.ib67.prts.user.User;
import io.ib67.prts.user.UserToProject;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
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

    /** Lookup used when resolving an OIDC login to a local account. */
    public static Optional<OAuthIdentity> findByIssuerAndSubject(String issuer, String subject) {
        return findByIdOptional(new Id(issuer, subject));
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
