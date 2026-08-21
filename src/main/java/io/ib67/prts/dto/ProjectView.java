package io.ib67.prts.dto;

import io.ib67.prts.project.Project;
import io.ib67.prts.project.ProjectRole;

import java.util.UUID;

/**
 * @param role the <em>caller's</em> role in this project, {@link ProjectRole#NONE} when they hold
 *             none — an admin reading a project they are not a member of sees exactly that.
 */
public record ProjectView(
        UUID id,
        String name,
        ProjectRole role
) {
    public static ProjectView of(Project project, ProjectRole role) {
        return new ProjectView(project.getId(), project.getName(), role);
    }
}
