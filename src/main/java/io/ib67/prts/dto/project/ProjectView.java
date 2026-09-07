package io.ib67.prts.dto.project;

import io.ib67.prts.project.entity.Project;
import io.ib67.prts.project.entity.ProjectRole;

import java.util.Objects;
import java.util.UUID;

/**
 * Summary view of a project.
 *
 * @param role The caller's role in the project, or {@link ProjectRole#NONE} if not a direct member.
 */
public record ProjectView(
        UUID id,
        String name,
        ProjectRole role
) {
    public ProjectView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
    }

    public static ProjectView of(Project project, ProjectRole role) {
        return new ProjectView(project.getId(), project.getName(), role);
    }
}
