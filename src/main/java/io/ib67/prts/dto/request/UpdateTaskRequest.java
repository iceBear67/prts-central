package io.ib67.prts.dto.request;

import io.ib67.prts.job.task.TaskScope;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to update a task.
 *
 * <p>Providing a new {@code scope} replaces the existing task scope.
 *
 * @param name        New name, or null to retain current value.
 * @param description New description, or null to retain current value (blank clears).
 * @param trackedAt   New source tracking URL, or null to retain current value (blank clears).
 * @param scope       New execution scope, or null to retain current value.
 */
public record UpdateTaskRequest(
        @Nullable @Size(min = 1, message = "name is required") String name,
        @Nullable @Size(max = 256, message = "description must be at most 256 characters")
        String description,
        @Nullable @Size(max = 512, message = "trackedAt must be at most 512 characters")
        @Pattern(regexp = CreateTaskRequest.TRACKED_AT, message = CreateTaskRequest.TRACKED_AT_REJECTED)
        String trackedAt,
        @Nullable @Valid TaskScope scope
) {
    public UpdateTaskRequest {
        name = name == null ? null : name.strip();
        description = description == null ? null : description.strip();
        trackedAt = trackedAt == null ? null : trackedAt.strip();
        if (name == null && description == null && trackedAt == null && scope == null) {
            throw new BadRequestException(
                    "name, description, trackedAt or scope is required; a blank value clears it");
        }
    }
}
