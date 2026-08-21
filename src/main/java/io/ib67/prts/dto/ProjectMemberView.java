package io.ib67.prts.dto;

import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.user.UserToProject;

import java.util.UUID;

public record ProjectMemberView(
        UUID userId,
        String name,
        String email,
        ProjectRole role
) {
    /** Reads {@code link.getUser()}, so the membership must have been fetched with its user. */
    public static ProjectMemberView of(UserToProject link) {
        var user = link.getUser();
        return new ProjectMemberView(user.getId(), user.getName(), user.getEmail(), link.getProjectRole());
    }
}
