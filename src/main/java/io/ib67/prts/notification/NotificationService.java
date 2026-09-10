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
 * Leaves messages for users on behalf of the components of the system.
 */
@ApplicationScoped
public class NotificationService {

    /**
     * Leaves a message for a recipient who may since have been deleted.
     *
     * <p>Separate from {@link #notify} because a caller reporting on its own work cannot catch the miss:
     * throwing out of a {@code @Transactional} method that joined the caller's transaction marks it
     * rollback-only, so the message about the work would undo the work.
     *
     * @return empty if neither the recipient nor, for a sub-account, its creator still exists
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
     * Leaves the same message for everyone who can log in, one row each. Sub-accounts are skipped:
     * nobody signs in as one, so nobody would ever read it.
     *
     * @return how many recipients it reached
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
