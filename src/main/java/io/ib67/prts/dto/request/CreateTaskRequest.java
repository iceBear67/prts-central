package io.ib67.prts.dto.request;

import io.ib67.prts.job.task.TaskScope;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload to open a task.
 *
 * @param scope values the task contributes to its jobs; omitted means it contributes none
 */
public record CreateTaskRequest(
        @NotBlank(message = "name is required")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,
        @Nullable TaskScope scope
) {
    public CreateTaskRequest {
        name = name == null ? null : name.strip();
    }

    public TaskScope scopeOrEmpty() {
        return scope == null ? TaskScope.EMPTY : scope;
    }
}
