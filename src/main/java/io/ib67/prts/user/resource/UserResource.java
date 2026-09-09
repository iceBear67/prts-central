package io.ib67.prts.user.resource;

import io.ib67.prts.dto.CurrentUserView;
import io.ib67.prts.dto.ScopedGrants;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import io.ib67.prts.user.UserToProject;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * REST endpoint exposing the account a request authenticated as.
 */
@Path("/user")
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    PermissionService permissionService;
    @Inject
    UserService userService;
    @Inject
    UserContext userContext;

    /** Returns the caller's own account, the roles it holds, and the permissions granted to it. */
    @GET
    @Transactional
    public CurrentUserView getCurrentUser() {
        var user = userContext.require();
        var grants = ScopedGrants.of(permissionService.grantsOf(user.getId()));
        return CurrentUserView.of(
                user,
                SubAccount.owningProjectOf(user.getId()).orElse(null),
                grants.global(),
                projectAccess(userService.listMemberships(user.getId()), grants.byProject()));
    }

    /** Merges roles with the grants made on top of them; either one alone reaches a project. */
    private static Map<UUID, CurrentUserView.ProjectAccess> projectAccess(
            List<UserToProject> memberships, Map<UUID, List<String>> grants) {
        var access = new TreeMap<UUID, CurrentUserView.ProjectAccess>();
        grants.forEach((projectId, permissions) -> access.put(
                projectId, new CurrentUserView.ProjectAccess(ProjectRole.NONE, permissions)));
        memberships.forEach(link -> {
            var projectId = link.getProject().getId();
            access.put(projectId, new CurrentUserView.ProjectAccess(
                    link.getProjectRole(), grants.getOrDefault(projectId, List.of())));
        });
        return access;
    }
}
