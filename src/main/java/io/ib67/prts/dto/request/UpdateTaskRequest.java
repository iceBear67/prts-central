package io.ib67.prts.dto.request;

import io.ib67.prts.job.task.TaskScope;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to edit a task.
 *
 * <p>A new scope replaces the previous one outright rather than merging into it, so a caller clears a
 * contribution by leaving it out of the scope it sends. Only jobs created afterwards see the change —
 * a job's spec is merged when it is enqueued and again when it is dispatched.
 *
 * @param name        New name. Null keeps the existing one.
 * @param description New description, or blank to clear. Null keeps the existing one.
 * @param trackedAt   New source link, or blank to clear. Null keeps the existing one.
 * @param scope       New scope. Null keeps the existing one.
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
