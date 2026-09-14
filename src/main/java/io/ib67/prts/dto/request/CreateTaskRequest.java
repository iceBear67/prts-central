package io.ib67.prts.dto.request;

import io.ib67.prts.job.task.TaskScope;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload to create a task.
 *
 * @param description Task summary; empty string if omitted.
 * @param trackedAt   External issue or pull request URL; empty string if omitted.
 * @param scope       Execution attributes injected into jobs run under this task.
 */
public record CreateTaskRequest(
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,
        @Nullable @Size(max = 256, message = "description must be at most 256 characters")
        String description,
        @Nullable @Size(max = 512, message = "trackedAt must be at most 512 characters")
        @Pattern(regexp = CreateTaskRequest.TRACKED_AT, message = CreateTaskRequest.TRACKED_AT_REJECTED)
        String trackedAt,
        @Nullable @Valid TaskScope scope
) {
    /** Regex pattern matching an optional HTTP/HTTPS URL. */
    public static final String TRACKED_AT = "(https?://\\S+)?";

    static final String TRACKED_AT_REJECTED = "trackedAt must be an http or https URL";

    public CreateTaskRequest {
        name = name == null ? null : name.strip();
        description = description == null ? "" : description.strip();
        trackedAt = trackedAt == null ? "" : trackedAt.strip();
    }

    public TaskScope scopeOrEmpty() {
        return scope == null ? TaskScope.EMPTY : scope;
    }
}
