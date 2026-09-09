package io.ib67.prts.user;

import io.ib67.prts.project.entity.Project;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Represents a programmatic sub-account (service account) tied to a project rather than a human user.
 */
@Entity
@Table(
        name = "sub_account",
        indexes = @Index(name = "idx_sub_account_project_id", columnList = "project_id"))
@Getter
@Setter
@NoArgsConstructor
@ToString
public class SubAccount extends PanacheEntityBase {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private Project project;

    /** User ID of the creator for audit purposes. */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static SubAccount of(User user, Project project, UUID createdBy) {
        var account = new SubAccount();
        account.userId = user.getId();
        account.user = user;
        account.project = project;
        account.createdBy = createdBy;
        return account;
    }

    /** Finds a sub-account by user ID within a specific project. */
    public static Optional<SubAccount> findInProject(UUID projectId, UUID userId) {
        return find("from SubAccount s join fetch s.user where s.userId = ?1 and s.project.id = ?2",
                userId, projectId).firstResultOptional();
    }

    public static List<SubAccount> listByProjectFetched(UUID projectId) {
        return find("from SubAccount s join fetch s.user where s.project.id = ?1", projectId).list();
    }

    public static boolean isSubAccount(UUID userId) {
        return count("userId", userId) > 0;
    }

    /** Returns the project owning the account, or empty if the user is not a sub-account. */
    public static Optional<UUID> owningProjectOf(UUID userId) {
        return getEntityManager()
                .createQuery("select s.project.id from SubAccount s where s.userId = ?1", UUID.class)
                .setParameter(1, userId)
                .getResultStream()
                .findFirst();
    }

    /** Maps sub-account IDs to their owning project IDs; users who are not sub-accounts are omitted. */
    public static Map<UUID, UUID> owningProjectsOf(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return getEntityManager()
                .createQuery("select s.userId, s.project.id from SubAccount s where s.userId in ?1",
                        Object[].class)
                .setParameter(1, userIds)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (UUID) row[1]));
    }
}
