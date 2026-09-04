package io.ib67.prts.project;

import io.ib67.prts.Perm;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.UserContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * The one answer to "may this caller create jobs here", for the views that show more to a caller who
 * could act on what they are reading. Kept in one bean rather than per resource: it mirrors the
 * {@code @RequirePermission} on the create endpoint by hand, and a second copy would be a second
 * thing to remember when that rule changes.
 */
@ApplicationScoped
public class JobCreateAccess {

    @Inject
    UserContext userContext;
    @Inject
    PermissionService permissionService;

    public boolean allowedIn(UUID projectId) {
        var user = userContext.get();
        return user != null
                && permissionService.allows(user.getId(), Perm.JOB_CREATE, projectId, ProjectRole.MEMBER);
    }
}
