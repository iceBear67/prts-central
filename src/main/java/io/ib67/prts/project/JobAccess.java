package io.ib67.prts.project;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * The answers to "what may this caller see of a job here", for the views whose detail scales with
 * permission instead of splitting into a second endpoint. Each mirrors a {@code @RequirePermission}
 * rule by hand and is kept in one bean so there is one thing to remember when that rule changes.
 */
@ApplicationScoped
public class JobAccess {

    @Inject
    UserContext userContext;
    @Inject
    PermissionService permissionService;

    /** The create endpoint's gate: what seeing a stored create request costs. */
    public boolean mayCreate(UUID projectId) {
        return allows(Perm.JOB_CREATE, projectId, ProjectRole.MEMBER);
    }

    /**
     * Template content — the spec and resource class — against the id and name every reader gets.
     * No role stands in: an explicit {@code job:template:read} grant, or {@code admin:all}.
     */
    public boolean mayReadTemplate(UUID projectId) {
        return allows(Perm.JOB_TEMPLATE_READ, projectId, ProjectRole.NONE);
    }

    private boolean allows(Perm perm, UUID projectId, ProjectRole defaultRole) {
        var user = userContext.get();
        return user != null && permissionService.allows(user.getId(), perm, projectId, defaultRole);
    }
}
