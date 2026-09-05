package io.ib67.prts.user;

import io.ib67.prts.auth.OAuthIdentity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A local account. Login credentials live in {@link OAuthIdentity}; this table only holds profile
 * data, so a user may be linked to several identity providers.
 */
@Entity
// "user" is reserved in PostgreSQL, so the name stays quoted exactly as in import.sql.
@Table(name = "prts_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class User extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    @Column(name = "email", nullable = false, columnDefinition = "varchar")
    private String email;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The schema does not declare {@code email} unique, so this deliberately returns the first
     * match instead of failing on duplicates. A blank address is nobody: sub-accounts carry one
     * because they have no address and the column is not null, and they must not be resolvable by it.
     */
    public static Optional<User> findByEmail(String email) {
        return email == null || email.isBlank()
                ? Optional.empty()
                : find("email", email).firstResultOptional();
    }
}
