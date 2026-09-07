package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.project.ProjectDetailView;
import io.ib67.prts.dto.project.ProjectMemberView;
import io.ib67.prts.dto.project.ProjectView;
import io.ib67.prts.dto.request.RenameProjectRequest;
import io.ib67.prts.dto.request.SetMemberRoleRequest;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
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
 * membership needs no permission at all, and is the same {@code DELETE} as removing anyone else.
 */
@Path("/project")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectResource {
    @Inject
    ProjectService projectService;
    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;
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

    /** The roster and the job counts too, and how the caller came to see them — see {@link ProjectDetailView}. */
    @GET
    @Path("/{projectId}")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public ProjectDetailView getProject(@ProjectId @PathParam("projectId") UUID projectId) {
        var project = projectService.require(projectId);
        var userId = requireUser().getId();
        var role = userService.roleOf(userId, projectId);
        var access = role != ProjectRole.NONE ? ProjectDetailView.Access.MEMBER
                : permissionService.isAdmin(userId) ? ProjectDetailView.Access.ADMIN
                : ProjectDetailView.Access.PERMISSION;
        var members = userService.listMembers(projectId).stream().map(ProjectMemberView::of).toList();
        var counts = Job.countByProject(projectId);
        var jobs = new ProjectDetailView.Jobs(
                counts.visible(), counts.running(), PendingJob.countActive(projectId));
        return ProjectDetailView.of(project, role, access, members, jobs);
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

    /**
     * Takes the project and everything it owns, including the containers still running on workers and
     * the artifact objects in S3 — see {@link ProjectService#delete}. Not {@code project:update}: this
     * is strictly more than the rename that permission covers, and there is no undoing it.
     */
    @DELETE
    @Path("/{projectId}")
    @RequirePermission(value = Perm.PROJECT_DELETE, defaultRole = ProjectRole.OWNER)
    public void deleteProject(@ProjectId @PathParam("projectId") UUID projectId) {
        if (!projectService.delete(projectId)) {
            throw new NotFoundException("no such project: " + projectId);
        }
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

    /**
     * Removing anyone else takes the permission; removing yourself takes none, which is why the
     * interceptor lets every caller through and the body draws the line. A last owner cannot leave
     * either way — {@link UserService#revoke} refuses with a 409, because a project with an empty
     * roster could never be administered again. Disbanding is {@link #deleteProject}, not this.
     */
    @DELETE
    @Path("/{projectId}/member/{userId}")
    @RequirePermission(value = Perm.PROJECT_MEMBER_MANAGE, defaultRole = ProjectRole.OWNER, defaultValue = true)
    public void removeMember(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        var callerId = requireUser().getId();
        if (!callerId.equals(userId)
                && !permissionService.allows(callerId, Perm.PROJECT_MEMBER_MANAGE, projectId, ProjectRole.OWNER)) {
            throw new ForbiddenException("missing permission: " + Perm.PROJECT_MEMBER_MANAGE.permission());
        }
        if (!userService.revoke(userId, projectId)) {
            throw new NotFoundException("not a member of project " + projectId + ": " + userId);
        }
    }

    private ProjectRole roleOf(UUID projectId) {
        return userService.roleOf(requireUser().getId(), projectId);
    }

    private User requireUser() {
        return userContext.require();
    }
}
