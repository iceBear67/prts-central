package io.ib67.prts;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A permission and the scope it is held in.
 *
 * <p>A {@link #global()} permission is held once for the whole system and its grant row is scoped to
 * {@link io.ib67.prts.user.Permission#GLOBAL}. Every other permission is held per project and only
 * ever matches the project it was granted in, so reach across projects belongs to
 * {@link #ADMIN_OF_ALL} alone.
 */
public enum Perm {
    ADMIN_OF_ALL("admin:all", true),

    PROJECT_READ("project:read", false),
    PROJECT_UPDATE("project:update", false),
    /** Separate from {@link #PROJECT_UPDATE}: a delete tears down the project's jobs and artifacts. */
    PROJECT_DELETE("project:delete", false),
    PROJECT_MEMBER_MANAGE("project:member:manage", false),
    PROJECT_SUBACCOUNT_MANAGE("project:subaccount:manage", false),
    /** Which secrets a project has, by name — never a value. */
    PROJECT_SECRET_READ("project:secret:read", false),
    PROJECT_SECRET_MANAGE("project:secret:manage", false),

    JOB_READ("job:read", false),
    JOB_LOG_READ("job:log:read", false),
    JOB_ARTIFACT_READ("job:artifact:read", false),
    JOB_TEMPLATE_READ("job:template:read", false),
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

    /** Stored in {@code user_permission}, so these strings are part of the schema, not labels. */
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
     * By the stored string rather than the constant name: that string is what a caller naming a
     * permission over the wire spells, and what {@code user_permission} already holds.
     */
    public static Optional<Perm> byPermission(String permission) {
        return Optional.ofNullable(BY_PERMISSION.get(permission));
    }
}
