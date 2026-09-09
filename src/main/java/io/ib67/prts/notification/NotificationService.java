package io.ib67.prts.notification;

import io.ib67.prts.notification.entity.Notification;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Leaves messages for users on behalf of the components of the system.
 */
@ApplicationScoped
public class NotificationService {

    /**
     * Leaves a message for one user.
     *
     * <p>Nobody signs in as a sub-account, so a message addressed to one is handed to whoever created
     * it, with the sub-account's name carried in {@code from} so the reader can tell which it concerns.
     * Its creator is always a person: holding {@code project:subaccount:manage} is what it takes to
     * make one, and a sub-account may not hold it.
     */
    @Transactional
    public Notification notify(UUID recipient, String from, String title, String content) {
        var account = SubAccount.findFetched(recipient).orElse(null);
        if (account == null) {
            return leave(requireUser(recipient), from, title, content);
        }
        return leave(requireUser(account.getCreatedBy()),
                from + " (" + account.getUser().getName() + ")", title, content);
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

    private static User requireUser(UUID id) {
        return User.<User>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such user: " + id));
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
