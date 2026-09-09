package io.ib67.prts.notification.entity;

import io.ib67.prts.user.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A message left for a user by a component of the system.
 *
 * <p>A site-wide notice is fanned out, one row per recipient, so that each carries its own read state.
 */
@Entity
@Table(
        name = "notification",
        indexes = @Index(name = "idx_notification_recipient_id", columnList = "recipient_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Notification extends PanacheEntityBase {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @ToString.Exclude
    private User recipient;

    /** Who left the message. Named sender because {@code from} is a reserved word in SQL. */
    @Column(name = "sender", nullable = false, updatable = false, columnDefinition = "varchar")
    private String sender;

    @Column(name = "title", nullable = false, updatable = false, columnDefinition = "varchar")
    private String title;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Lists a user's messages, most recent first. */
    public static List<Notification> listByRecipient(UUID recipient, int offset, int limit) {
        return Notification.<Notification>find(
                        "recipient.id = ?1 order by createdAt desc, id desc", recipient)
                .range(offset, offset + limit - 1)
                .list();
    }

    /** Resolves a message only if it belongs to the given user. */
    public static Optional<Notification> findForRecipient(UUID recipient, UUID id) {
        return Notification.<Notification>find("id = ?1 and recipient.id = ?2", id, recipient)
                .firstResultOptional();
    }
}
