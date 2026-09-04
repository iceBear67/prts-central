package io.ib67.prts.dto;

import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.user.UserToProject;

import java.util.Objects;
import java.util.UUID;

/** No address: a roster is a read for every viewer of the project, and the email is a login key. */
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

    /** Reads {@code link.getUser()}, so the membership must have been fetched with its user. */
    public static ProjectMemberView of(UserToProject link) {
        var user = link.getUser();
        return new ProjectMemberView(user.getId(), user.getName(), link.getProjectRole());
    }
}
