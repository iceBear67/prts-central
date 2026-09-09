package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.Pages;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.ScopedGrants;
import io.ib67.prts.dto.admin.UserDetailView;
import io.ib67.prts.dto.admin.UserView;
import io.ib67.prts.dto.request.SetPermissionsRequest;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserService;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Administrative endpoints for managing users and permissions across projects.
 */
@Path("/admin/user")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminUserResource {

    @Inject
    EntityManager entityManager;
    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;
    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public List<UserView> listUsers(
            @QueryParam("query") @Nullable String query,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") @Nullable Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var users = User.search(query, Pages.clampOffset(offset, window), window);
        var owners = owningProjects(users.stream().map(User::getId).toList());
        return users.stream().map(user -> UserView.of(user, owners.get(user.getId()))).toList();
    }

    /** Maps sub-account IDs to their owning project IDs; human users are omitted. */
    private Map<UUID, UUID> owningProjects(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return entityManager
                .createQuery("select s.userId, s.project.id from SubAccount s where s.userId in ?1",
                        Object[].class)
                .setParameter(1, userIds)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (UUID) row[1]));
    }

    @GET
    @Path("/{userId}")
    @Transactional
    public UserDetailView getUser(@PathParam("userId") UUID userId) {
        return detailOf(userId);
    }

    /** Replaces the user's global permissions. */
    @PUT
    @Path("/{userId}/permission/global")
    @Consumes(MediaType.APPLICATION_JSON)
    public UserDetailView setGlobalPermissions(
            @PathParam("userId") UUID userId,
            @NotNull(message = "a request body is required") @Valid SetPermissionsRequest request) {
        var perms = request.resolved();
        requireUser(userId);
        userService.setGlobalPermissions(userId, perms);
        return detailOf(userId);
    }

    /** Replaces the user's permissions for a specific project. */
    @PUT
    @Path("/{userId}/permission/project/{projectId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public UserDetailView setProjectPermissions(
            @PathParam("userId") UUID userId,
            @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid SetPermissionsRequest request) {
        var perms = request.resolved();
        requireUser(userId);
        userService.setPermissions(userId, projectId, perms);
        return detailOf(userId);
    }

    /** Revokes all permissions granted to the user across all scopes. */
    @DELETE
    @Path("/{userId}/permission")
    public UserDetailView revokePermissions(@PathParam("userId") UUID userId) {
        requireUser(userId);
        userService.revokeAllPermissions(userId);
        return detailOf(userId);
    }

    /**
     * Retrieves the detailed view for a user.
     *
     * <p>Called outside mutating service transactions so that cached permission lookups reflect
     * committed changes.
     */
    private UserDetailView detailOf(UUID userId) {
        var user = requireUser(userId);
        var grants = ScopedGrants.of(permissionService.grantsOf(userId));
        var memberships = userService.listMemberships(userId).stream()
                .map(UserDetailView.Membership::of)
                .sorted(Comparator.comparing(UserDetailView.Membership::projectName))
                .toList();
        return new UserDetailView(
                UserView.of(user, owningProjects(List.of(userId)).get(userId)),
                memberships,
                grants.global(),
                grants.byProject());
    }

    private static User requireUser(UUID userId) {
        return User.<User>findByIdOptional(userId)
                .orElseThrow(() -> new NotFoundException("no such user: " + userId));
    }
}
