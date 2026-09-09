package io.ib67.prts.dto;

import io.ib67.prts.notification.entity.Notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View of a message left for the calling user.
 */
public record NotificationView(
        UUID id,
        String from,
        String title,
        String content,
        boolean read,
        Instant createdAt
) {
    public NotificationView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static NotificationView of(Notification notification) {
        return new NotificationView(
                notification.getId(),
                notification.getSender(),
                notification.getTitle(),
                notification.getContent(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
