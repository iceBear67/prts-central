package io.ib67.prts.secret.user;

import io.ib67.prts.user.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stored hash and metadata for a user's personal access token.
 */
@Entity
@Table(
        name = "user_access_token",
        indexes = @Index(name = "idx_user_access_token_hash", columnList = "token_hash", unique = true))
@Getter
@Setter
@NoArgsConstructor
@ToString
public class UserAccessToken extends PanacheEntityBase {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private User user;

    @Column(name = "token_hash", nullable = false, columnDefinition = "varchar")
    @ToString.Exclude
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    public static UserAccessToken of(User user, String tokenHash) {
        var token = new UserAccessToken();
        token.userId = user.getId();
        token.user = user;
        token.tokenHash = tokenHash;
        token.issuedAt = Instant.now();
        return token;
    }

    /** Finds the associated user for a given token hash. */
    public static Optional<User> findUserByHash(String tokenHash) {
        return getEntityManager()
                .createQuery("select t.user from UserAccessToken t where t.tokenHash = ?1", User.class)
                .setParameter(1, tokenHash)
                .getResultStream()
                .findFirst();
    }
}
