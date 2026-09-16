package io.ib67.prts.job;

import io.ib67.prts.Perm;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.user.Permission;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluates caller permissions for selectively displaying sensitive job details in views.
 */
@ApplicationScoped
public class JobAccess {

    @Inject
    UserContext userContext;
    @Inject
    PermissionService permissionService;
    @Inject
    UserService userService;

    /** Checks whether the caller is permitted to submit or view job create requests in the project. */
    public boolean mayCreate(UUID projectId) {
        return allows(Perm.JOB_CREATE, projectId, ProjectRole.MEMBER);
    }

    /** Checks whether the caller is permitted to read template specs and resource classes. */
    public boolean mayReadTemplate(UUID projectId) {
        return allows(Perm.JOB_TEMPLATE_READ, projectId, ProjectRole.NONE);
    }

    /** Checks whether the caller may view a job's ACP session. */
    public boolean mayReadAgent(UUID projectId) {
        return allows(Perm.JOB_AGENT_READ, projectId, ProjectRole.VIEWER);
    }

    /** Checks whether the caller may interact with a job's ACP agent. */
    public boolean mayInteractWithAgent(UUID projectId) {
        return allows(Perm.JOB_AGENT_INTERACT, projectId, ProjectRole.MEMBER);
    }

    /**
     * The projects whose jobs the caller may read, or null for every project.
     *
     * <p>Candidates are the projects the caller is a member of and those they hold a grant in — either
     * one alone reaches a project — each then put through the same gate the per-project listing uses,
     * so a ban on {@code job:read} withholds the cross-project feed too. Null rather than "all
     * projects" because {@code admin:all} needs no predicate at all, and an empty set means none.
     */
    @Nullable
    public Set<UUID> readableProjects() {
        var userId = userContext.require().getId();
        if (permissionService.isAdmin(userId)) {
            return null;
        }
        var candidates = new HashSet<UUID>();
        userService.listMemberships(userId)
                .forEach(link -> candidates.add(link.getProject().getId()));
        permissionService.grantsOf(userId).stream()
                .map(Permission.Id::getProjectId)
                .filter(scope -> !Permission.GLOBAL.equals(scope))
                .forEach(candidates::add);
        candidates.removeIf(projectId -> !permissionService.allows(
                userId, Perm.JOB_READ, projectId, ProjectRole.VIEWER));
        return candidates;
    }

    private boolean allows(Perm perm, UUID projectId, ProjectRole defaultRole) {
        var user = userContext.get();
        return user != null && permissionService.allows(user.getId(), perm, projectId, defaultRole);
    }
}
