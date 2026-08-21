package io.ib67.prts;

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
    PROJECT_MEMBER_MANAGE("project:member:manage", false),

    JOB_READ("job:read", false),
    JOB_LOG_READ("job:log:read", false),
    JOB_ARTIFACT_READ("job:artifact:read", false),
    JOB_TEMPLATE_READ("job:template:read", false),
    JOB_CREATE("job:create", false),
    JOB_CANCEL("job:cancel", false),
    JOB_SPEC_IMAGE("job:spec:image", false),
    JOB_SPEC_ENVIRONMENT("job:spec:environment", false),
    JOB_SPEC_SECRETS("job:spec:secrets", false),
    JOB_SPEC_LABELS("job:spec:labels", false),
    JOB_SPEC_COMMAND("job:spec:command", false),
    JOB_SPEC_VOLUMES("job:spec:volumes", false),
    JOB_SPEC_TIMEOUT("job:spec:timeout", false),
    JOB_SPEC_LOCK("job:spec:lock", false),
    JOB_RESOURCE_CLASS("job:resource-class", false);

    /** Stored in {@code user_permission}, so these strings are part of the schema, not labels. */
    private final String permission;
    private final boolean global;

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
}
