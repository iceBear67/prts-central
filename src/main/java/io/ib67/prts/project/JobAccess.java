package io.ib67.prts.project;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    /** Checks whether the caller is permitted to submit or view job create requests in the project. */
    public boolean mayCreate(UUID projectId) {
        return allows(Perm.JOB_CREATE, projectId, ProjectRole.MEMBER);
    }

    /** Checks whether the caller is permitted to read template specs and resource classes. */
    public boolean mayReadTemplate(UUID projectId) {
        return allows(Perm.JOB_TEMPLATE_READ, projectId, ProjectRole.NONE);
    }

    private boolean allows(Perm perm, UUID projectId, ProjectRole defaultRole) {
        var user = userContext.get();
        return user != null && permissionService.allows(user.getId(), perm, projectId, defaultRole);
    }
}
