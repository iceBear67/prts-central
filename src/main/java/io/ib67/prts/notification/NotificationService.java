package io.ib67.prts.notification;

import io.ib67.prts.notification.entity.Notification;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for delivering system notifications to users.
 */
@ApplicationScoped
public class NotificationService {

    /**
     * Sends a notification if the recipient (or owning creator for a sub-account) exists.
     *
     * <p>Returns {@link Optional#empty()} instead of throwing to prevent marking the caller's
     * joined transaction as rollback-only when the recipient no longer exists.
     *
     * @return the created notification, or empty if the recipient no longer exists
     */
    @Transactional
    public Optional<Notification> notifyIfPresent(
            UUID recipient, String from, String title, String content) {
        var account = SubAccount.findFetched(recipient).orElse(null);
        var target = account == null ? recipient : account.getCreatedBy();
        var sender = account == null ? from : from + " (" + account.getUser().getName() + ")";
        return User.<User>findByIdOptional(target).map(user -> leave(user, sender, title, content));
    }

    /**
     * Broadcasts a notification to all non-sub-account users.
     *
     * @return number of notifications created
     */
    @Transactional
    public int broadcast(String from, String title, String content) {
        var recipients = User.listPeople();
        recipients.forEach(user -> leave(user, from, title, content));
        return recipients.size();
    }

    @Transactional
    public void setRead(UUID recipient, UUID notificationId, boolean read) {
        Notification.findForRecipient(recipient, notificationId)
                .orElseThrow(() -> new NotFoundException("no such notification: " + notificationId))
                .setRead(read);
    }

    private static Notification leave(User recipient, String from, String title, String content) {
        var notification = Notification.builder()
                .recipient(recipient)
                .sender(from)
                .title(title)
                .content(content)
                .build();
        notification.persist();
        return notification;
    }
}
