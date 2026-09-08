package io.ib67.prts.dto.project;

import io.ib67.prts.project.entity.Project;
import io.ib67.prts.project.entity.ProjectRole;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Summary view of a project.
 *
 * @param role       The caller's role in the project, or {@link ProjectRole#NONE} if not a direct member.
 * @param archivedAt Timestamp when the project was archived, or null if active.
 */
public record ProjectView(
        UUID id,
        String name,
        ProjectRole role,
        @Nullable Instant archivedAt
) {
    public ProjectView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
    }

    public static ProjectView of(Project project, ProjectRole role) {
        return new ProjectView(project.getId(), project.getName(), role, project.getArchivedAt());
    }
}
