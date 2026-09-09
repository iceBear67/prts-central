package io.ib67.prts.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload to leave a message.
 *
 * @param from who the message is attributed to
 */
public record CreateNotificationRequest(
        @NotBlank(message = "from is required")
        @Size(max = 100, message = "from must be at most 100 characters")
        String from,
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,
        @NotBlank(message = "content is required")
        @Size(max = 4000, message = "content must be at most 4000 characters")
        String content
) {
    public CreateNotificationRequest {
        from = from == null ? null : from.strip();
        title = title == null ? null : title.strip();
        content = content == null ? null : content.strip();
    }
}
