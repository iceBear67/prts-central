package io.ib67.prts.dto.admin;

import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.user.UserToProject;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative view of an account with everything it may act through.
 *
 * @param globalPermissions  Grants scoped system-wide, such as {@code admin:all}.
 * @param projectPermissions Grants keyed by the project they apply in.
 */
public record UserDetailView(
        UserView user,
        List<Membership> memberships,
        List<String> globalPermissions,
        Map<UUID, List<String>> projectPermissions
) {
    public UserDetailView {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(memberships, "memberships");
        Objects.requireNonNull(globalPermissions, "globalPermissions");
        Objects.requireNonNull(projectPermissions, "projectPermissions");
    }

    public record Membership(UUID projectId, String projectName, ProjectRole role) {
        public Membership {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(projectName, "projectName");
            Objects.requireNonNull(role, "role");
        }

        public static Membership of(UserToProject link) {
            return new Membership(
                    link.getProject().getId(), link.getProject().getName(), link.getProjectRole());
        }
    }
}
