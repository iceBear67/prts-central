package io.ib67.prts.dto.admin;

import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.user.UserToProject;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Detailed administrative view of a user account, including memberships and permissions.
 *
 * @param globalPermissions  Global permission identifiers (e.g. {@code admin:all}).
 * @param projectPermissions Project-scoped permission identifiers grouped by project ID.
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
