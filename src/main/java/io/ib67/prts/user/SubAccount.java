package io.ib67.prts.user;

import io.ib67.prts.project.entity.Project;
import io.ib67.prts.project.entity.ProjectRole;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Marks a {@link User} as owned by a project rather than by a person: no {@link
 * io.ib67.prts.auth.OAuthIdentity} row, so no way to log in, and no {@link ProjectRole}, so it is
 * never on a roster and never counts toward the last-owner rule. What it may do is exactly the
 * {@link io.ib67.prts.Perm} set its project granted it.
 *
 * <p>A separate table rather than a column on {@code prts_user}, so both cascades are the database's:
 * deleting the user or the project takes the link with it, and the user's token and grants follow the
 * user.
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

    /** Audit only, by id and with no FK, like {@code Job.requestedBy}: the creator may be gone. */
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

    /**
     * Same rule as {@code JobService.findInProject}: found by its own id, then kept only if it belongs
     * to the project — so one project's owner cannot reach another's sub-account, or any other user.
     */
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
}
