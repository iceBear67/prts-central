package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.ProjectMemberView;
import io.ib67.prts.dto.ProjectView;
import io.ib67.prts.dto.RenameProjectRequest;
import io.ib67.prts.dto.SetMemberRoleRequest;
import io.ib67.prts.project.ProjectRole;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import io.quarkus.security.UnauthorizedException;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * The project itself and who is on it. Reading takes {@link ProjectRole#VIEWER}, changing the project
 * or its roster takes {@link ProjectRole#OWNER}. Leaving is the exception: dropping your own
 * membership needs no permission at all.
 */
@Path("/project")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectResource {
    @Inject
    ProjectService projectService;
    @Inject
    UserService userService;
    @Inject
    UserContext userContext;

    /**
     * The caller's own memberships — there is no project to scope a check to, and nothing here the
     * caller is not already part of. Admins see their own memberships too, not every project.
     */
    @GET
    @Transactional
    public List<ProjectView> listMyProjects() {
        return userService.listMemberships(requireUser().getId()).stream()
                .map(link -> ProjectView.of(link.getProject(), link.getProjectRole()))
                .toList();
    }

    @GET
    @Path("/{projectId}")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public ProjectView getProject(@ProjectId @PathParam("projectId") UUID projectId) {
        return ProjectView.of(projectService.require(projectId), roleOf(projectId));
    }

    @PATCH
    @Path("/{projectId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequirePermission(value = Perm.PROJECT_UPDATE, defaultRole = ProjectRole.OWNER)
    public ProjectView renameProject(
            @ProjectId @PathParam("projectId") UUID projectId, RenameProjectRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        return ProjectView.of(projectService.rename(projectId, request.name().strip()), roleOf(projectId));
    }

    @GET
    @Path("/{projectId}/member")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public List<ProjectMemberView> listMembers(@ProjectId @PathParam("projectId") UUID projectId) {
        // Not just for the 404: without it a project that does not exist would answer with an empty
        // roster, which reads as "nobody is on it".
        projectService.require(projectId);
        return userService.listMembers(projectId).stream()
                .map(ProjectMemberView::of)
                .toList();
    }

    /** Adds the user at that role, or moves an existing member to it. */
    @PUT
    @Path("/{projectId}/member/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequirePermission(value = Perm.PROJECT_MEMBER_MANAGE, defaultRole = ProjectRole.OWNER)
    public ProjectMemberView setMemberRole(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("userId") UUID userId,
            SetMemberRoleRequest request) {
        if (request == null || request.role() == null) {
            throw new BadRequestException("role is required");
        }
        if (request.role() == ProjectRole.NONE) {
            throw new BadRequestException("NONE is the absence of a membership; delete the member instead");
        }
        return ProjectMemberView.of(userService.grant(userId, projectId, request.role()));
    }

    @DELETE
    @Path("/{projectId}/member/{userId}")
    @RequirePermission(value = Perm.PROJECT_MEMBER_MANAGE, defaultRole = ProjectRole.OWNER)
    public void removeMember(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        if (!userService.revoke(userId, projectId)) {
            throw new NotFoundException("not a member of project " + projectId + ": " + userId);
        }
    }

    /** No permission: this only ever drops the caller's own membership. */
    @POST
    @Path("/{projectId}/leave")
    public void leaveProject(@PathParam("projectId") UUID projectId) {
        if (!userService.revoke(requireUser().getId(), projectId)) {
            throw new NotFoundException("not a member of project " + projectId);
        }
    }

    private ProjectRole roleOf(UUID projectId) {
        return userService.permissionOf(requireUser().getId(), projectId);
    }

    private User requireUser() {
        var user = userContext.get();
        if (user == null) {
            throw new UnauthorizedException();
        }
        return user;
    }
}
