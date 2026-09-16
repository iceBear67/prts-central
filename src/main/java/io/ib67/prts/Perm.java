package io.ib67.prts;

import jakarta.annotation.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Permissions, their scopes, and what each one lets its holder do.
 *
 * <p>A {@link #global()} permission applies system-wide (scoped to
 * {@link io.ib67.prts.user.Permission#GLOBAL}), such as {@link #ADMIN_OF_ALL}.
 * All other permissions are scoped to specific projects.
 *
 * <p>{@link #category()} and {@link #description()} are published through {@code PermissionView} so a
 * picker can group the catalogue and say what each entry hands over. They live here rather than in a
 * client-side table because a permission added below would otherwise appear as a bare identifier with
 * nothing failing to make that visible.
 */
public enum Perm {
    ADMIN_OF_ALL("admin:all", true, Category.ADMINISTRATION,
            "Full administrative access; passes every permission and project role check."),
    /** Global permission to create new projects. */
    PROJECT_CREATE("project:create", true, Category.PROJECTS,
            "Create new projects, becoming the owner of each."),

    PROJECT_READ("project:read", false, Category.PROJECTS,
            "View the project, its members and its catalogues."),
    PROJECT_UPDATE("project:update", false, Category.PROJECTS,
            "Rename the project or change its description."),
    /** Deleting a project permanently removes all its jobs and artifacts. */
    PROJECT_DELETE("project:delete", false, Category.PROJECTS,
            "Delete the project along with every job, artifact and secret in it."),
    /** Permission to archive or unarchive a project. */
    PROJECT_ARCHIVE("project:archive", false, Category.PROJECTS,
            "Archive the project read-only, stopping its work, or restore it."),
    /** Permission to transfer project ownership to another member. */
    PROJECT_TRANSFER("project:transfer", false, Category.PROJECTS,
            "Hand ownership of the project to another member."),
    PROJECT_MEMBER_MANAGE("project:member:manage", false, Category.PROJECTS,
            "Add members, change their roles and remove them."),
    PROJECT_SUBACCOUNT_MANAGE("project:subaccount:manage", false, Category.PROJECTS,
            "Create sub-accounts, set their permissions and issue their tokens."),
    /** View project secret names (never secret values). */
    PROJECT_SECRET_READ("project:secret:read", false, Category.PROJECTS,
            "List the names of the project's secrets. Values are never returned."),
    PROJECT_SECRET_MANAGE("project:secret:manage", false, Category.PROJECTS,
            "Create, update and delete the project's secrets."),
    /** Allocate and release project worker volumes. */
    PROJECT_VOLUME_MANAGE("project:volume:manage", false, Category.PROJECTS,
            "Allocate worker volumes for the project and release them."),

    TASK_READ("task:read", false, Category.TASKS,
            "View the project's tasks and the volumes they mount."),
    /** Create, edit, and close tasks, and manage volume mounts. */
    TASK_MANAGE("task:manage", false, Category.TASKS,
            "Open, edit and close tasks, and mount volumes into them."),

    JOB_READ("job:read", false, Category.JOBS,
            "View the project's jobs and its queue."),
    JOB_LOG_READ("job:log:read", false, Category.JOBS,
            "Read a job's execution log."),
    /** View live and recorded ACP sessions for a job. */
    JOB_AGENT_READ("job:agent:read", false, Category.JOBS,
            "Watch a job's agent session and read its recorded transcript."),
    /** Interact with a job's ACP agent (prompt, cancel, respond). */
    JOB_AGENT_INTERACT("job:agent:interact", false, Category.JOBS,
            "Prompt a job's agent, answer its requests and cancel its turn."),
    JOB_ARTIFACT_READ("job:artifact:read", false, Category.JOBS,
            "Download the files a job produced."),
    /** Permission to delete an artifact and its underlying storage object. */
    JOB_ARTIFACT_DELETE("job:artifact:delete", false, Category.JOBS,
            "Delete an artifact and the stored object behind it."),
    JOB_TEMPLATE_READ("job:template:read", false, Category.JOBS,
            "See a template's spec and resource class, not only its name."),
    JOB_TEMPLATE_MANAGE("job:template:manage", false, Category.JOBS,
            "Define and delete the project's own job templates."),
    JOB_CREATE("job:create", false, Category.JOBS,
            "Submit a job into the project's queue."),
    JOB_CANCEL("job:cancel", false, Category.JOBS,
            "Cancel a running job or a queued entry."),

    JOB_SPEC_IMAGE("job:spec:image", false, Category.JOB_SPEC,
            "Override the template's container image when submitting."),
    JOB_SPEC_DESCRIPTION("job:spec:description", false, Category.JOB_SPEC,
            "Override the template's description when submitting."),
    JOB_SPEC_ENVIRONMENT("job:spec:environment", false, Category.JOB_SPEC,
            "Override the template's environment variables when submitting."),
    JOB_SPEC_LABELS("job:spec:labels", false, Category.JOB_SPEC,
            "Override the template's labels when submitting."),
    JOB_SPEC_COMMAND("job:spec:command", false, Category.JOB_SPEC,
            "Append arguments to the template's command when submitting."),
    JOB_SPEC_VOLUMES("job:spec:volumes", false, Category.JOB_SPEC,
            "Override the template's volume mounts when submitting."),
    JOB_SPEC_TIMEOUT("job:spec:timeout", false, Category.JOB_SPEC,
            "Override the template's execution timeout when submitting."),
    JOB_SPEC_LOCK("job:spec:lock", false, Category.JOB_SPEC,
            "Override the template's mutual-exclusion lock when submitting."),
    JOB_RESOURCE_CLASS("job:resource-class", false, Category.JOB_SPEC,
            "Submit a job into a resource class other than its template's or task's.");

    /** Groupings a permission picker renders as sections. */
    private static final class Category {
        private static final String ADMINISTRATION = "Administration";
        private static final String PROJECTS = "Projects";
        private static final String TASKS = "Tasks";
        private static final String JOBS = "Jobs";
        private static final String JOB_SPEC = "Job specification";

        private Category() {
        }
    }

    /** The permission identifier stored in the database. */
    private final String permission;
    private final boolean global;
    private final String category;
    private final String description;

    private static final Map<String, Perm> BY_PERMISSION = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Perm::permission, Function.identity()));

    Perm(String permission, boolean global, String category, String description) {
        this.permission = permission;
        this.global = global;
        this.category = category;
        this.description = description;
    }

    public String permission() {
        return permission;
    }

    public boolean global() {
        return global;
    }

    public String category() {
        return category;
    }

    public String description() {
        return description;
    }

    /**
     * Resolves a permission by its string identifier.
     */
    public static Optional<Perm> byPermission(@Nullable String permission) {
        return permission == null ? Optional.empty() : Optional.ofNullable(BY_PERMISSION.get(permission));
    }
}
