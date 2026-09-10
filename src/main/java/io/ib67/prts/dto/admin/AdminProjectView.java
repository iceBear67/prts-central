package io.ib67.prts.dto.admin;

import io.ib67.prts.job.entity.Project;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative view of a project.
 *
 * @param jobs   Count of visible jobs.
 * @param queued Count of active queue entries.
 */
public record AdminProjectView(
        UUID id,
        String name,
        String description,
        @Nullable Instant archivedAt,
        long members,
        long jobs,
        long queued
) {
    public AdminProjectView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
    }

    public static AdminProjectView of(Project project, long members, long jobs, long queued) {
        return new AdminProjectView(project.getId(), project.getName(), project.getDescription(),
                project.getArchivedAt(), members, jobs, queued);
    }
}
