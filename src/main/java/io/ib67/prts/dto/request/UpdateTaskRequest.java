package io.ib67.prts.dto.request;

import io.ib67.prts.job.task.TaskScope;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BadRequestException;

/**
 * Request payload to edit a task.
 *
 * <p>A new scope replaces the previous one outright rather than merging into it, so a caller clears a
 * contribution by leaving it out of the scope it sends. Only jobs created afterwards see the change —
 * a job's spec is merged when it is enqueued and again when it is dispatched.
 *
 * @param name  New name. Null keeps the existing one.
 * @param scope New scope. Null keeps the existing one.
 */
public record UpdateTaskRequest(
        @Nullable @Size(min = 1, message = "name is required") String name,
        @Nullable TaskScope scope
) {
    public UpdateTaskRequest {
        name = name == null ? null : name.strip();
        if (name == null && scope == null) {
            throw new BadRequestException("name or scope is required");
        }
    }
}
