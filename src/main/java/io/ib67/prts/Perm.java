package io.ib67.prts;

import jakarta.annotation.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Permissions and their scopes.
 *
 * <p>A {@link #global()} permission applies system-wide (scoped to
 * {@link io.ib67.prts.user.Permission#GLOBAL}), such as {@link #ADMIN_OF_ALL}.
 * All other permissions are scoped to specific projects.
 */
public enum Perm {
    ADMIN_OF_ALL("admin:all", true),
    /** Global permission to create new projects. */
    PROJECT_CREATE("project:create", true),

    PROJECT_READ("project:read", false),
    PROJECT_UPDATE("project:update", false),
    /** Deleting a project permanently removes all its jobs and artifacts. */
    PROJECT_DELETE("project:delete", false),
    /** Permission to archive or unarchive a project. */
    PROJECT_ARCHIVE("project:archive", false),
    /** Permission to transfer project ownership to another member. */
    PROJECT_TRANSFER("project:transfer", false),
    PROJECT_MEMBER_MANAGE("project:member:manage", false),
    PROJECT_SUBACCOUNT_MANAGE("project:subaccount:manage", false),
    /** View project secret names (never secret values). */
    PROJECT_SECRET_READ("project:secret:read", false),
    PROJECT_SECRET_MANAGE("project:secret:manage", false),

    JOB_READ("job:read", false),
    JOB_LOG_READ("job:log:read", false),
    JOB_ARTIFACT_READ("job:artifact:read", false),
    /** Permission to delete an artifact and its underlying storage object. */
    JOB_ARTIFACT_DELETE("job:artifact:delete", false),
    JOB_TEMPLATE_READ("job:template:read", false),
    JOB_TEMPLATE_MANAGE("job:template:manage", false),
    JOB_CREATE("job:create", false),
    JOB_CANCEL("job:cancel", false),
    JOB_SPEC_IMAGE("job:spec:image", false),
    JOB_SPEC_ENVIRONMENT("job:spec:environment", false),
    JOB_SPEC_LABELS("job:spec:labels", false),
    JOB_SPEC_COMMAND("job:spec:command", false),
    JOB_SPEC_VOLUMES("job:spec:volumes", false),
    JOB_SPEC_TIMEOUT("job:spec:timeout", false),
    JOB_SPEC_LOCK("job:spec:lock", false),
    JOB_RESOURCE_CLASS("job:resource-class", false);

    /** The permission identifier stored in the database. */
    private final String permission;
    private final boolean global;

    private static final Map<String, Perm> BY_PERMISSION = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Perm::permission, Function.identity()));

    Perm(String permission, boolean global) {
        this.permission = permission;
        this.global = global;
    }

    public String permission() {
        return permission;
    }

    public boolean global() {
        return global;
    }

    /**
     * Resolves a permission by its string identifier.
     */
    public static Optional<Perm> byPermission(@Nullable String permission) {
        return permission == null ? Optional.empty() : Optional.ofNullable(BY_PERMISSION.get(permission));
    }
}
