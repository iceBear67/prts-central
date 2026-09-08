package io.ib67.prts.user;

import io.ib67.prts.auth.OAuthIdentity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a local user account in the system.
 */
@Entity
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

    /** Finds a user by email address, ignoring blank emails. */
    public static Optional<User> findByEmail(String email) {
        return email == null || email.isBlank()
                ? Optional.empty()
                : find("email", email).firstResultOptional();
    }

    /** Searches users by name or email with pagination, ordered by creation time descending. */
    public static List<User> search(@Nullable String query, int offset, int limit) {
        var filter = query == null || query.isBlank() ? "%" : "%" + query.strip().toLowerCase() + "%";
        return find("lower(name) like ?1 or lower(email) like ?1 order by id desc", filter)
                .range(offset, offset + limit - 1)
                .list();
    }
}
