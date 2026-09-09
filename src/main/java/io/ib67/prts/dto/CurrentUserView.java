package io.ib67.prts.dto;

import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.user.User;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The account a request authenticated as, and what it has been granted.
 *
 * @param subAccountOf      The project owning this account, or null for a person.
 * @param globalPermissions Permission identifiers held system-wide, {@code admin:all} among them.
 * @param projects          The caller's standing in every project they can reach, keyed by project ID.
 */
public record CurrentUserView(
        UUID id,
        String name,
        String email,
        Instant createdAt,
        @Nullable UUID subAccountOf,
        List<String> globalPermissions,
        Map<UUID, ProjectAccess> projects
) {
    public CurrentUserView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(globalPermissions, "globalPermissions");
        Objects.requireNonNull(projects, "projects");
    }

    public static CurrentUserView of(
            User user,
            @Nullable UUID subAccountOf,
            List<String> globalPermissions,
            Map<UUID, ProjectAccess> projects) {
        return new CurrentUserView(
                user.getId(), user.getName(), user.getEmail(), user.getCreatedAt(),
                subAccountOf, globalPermissions, projects);
    }

    /**
     * What the caller holds in one project.
     *
     * @param role        The role held, or {@link ProjectRole#NONE} when only a grant reaches the project.
     * @param permissions Permission identifiers granted on top of the role. The role's own implied
     *                    permissions are not listed here.
     */
    public record ProjectAccess(ProjectRole role, List<String> permissions) {
        public ProjectAccess {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(permissions, "permissions");
        }
    }
}
