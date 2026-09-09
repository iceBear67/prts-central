package io.ib67.prts.dto.project;

import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.user.UserToProject;

import java.util.Objects;
import java.util.UUID;

/** View representing a member of a project. */
public record ProjectMemberView(
        UUID userId,
        String name,
        ProjectRole role
) {
    public ProjectMemberView {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
    }

    public static ProjectMemberView of(UserToProject link) {
        var user = link.getUser();
        return new ProjectMemberView(user.getId(), user.getName(), link.getProjectRole());
    }
}
