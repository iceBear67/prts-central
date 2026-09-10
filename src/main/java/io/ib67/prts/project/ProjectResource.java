package io.ib67.prts.project;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.project.ProjectDetailView;
import io.ib67.prts.dto.project.ProjectMemberView;
import io.ib67.prts.dto.project.ProjectView;
import io.ib67.prts.dto.request.CreateProjectRequest;
import io.ib67.prts.dto.request.SetMemberRoleRequest;
import io.ib67.prts.dto.request.TransferProjectRequest;
import io.ib67.prts.dto.request.UpdateProjectRequest;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoint managing projects and member roles.
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

    /** Lists projects where the current user is a member. */
    @GET
    @Transactional
    public List<ProjectView> listMyProjects() {
        return userService.listMemberships(requireUser().getId()).stream()
                .map(link -> ProjectView.of(link.getProject(), link.getProjectRole()))
                .toList();
    }

    /** Creates a new project with the caller as its owner. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @RequirePermission(Perm.PROJECT_CREATE)
    public ProjectView createProject(
            @NotNull(message = "a request body is required") @Valid CreateProjectRequest request) {
        return ProjectView.of(
                projectService.create(request.name(), request.description(), requireUser().getId()),
                ProjectRole.OWNER);
    }

    /** Retrieves detailed project information, member roster, and job counts. */
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
    public ProjectView updateProject(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid UpdateProjectRequest request) {
        projectService.requireWritable(projectId);
        return ProjectView.of(
                projectService.update(projectId, request.name(), request.description()), roleOf(projectId));
    }

    /**
     * Archives a project, cancelling queued jobs and interrupting active ones before setting it to read-only.
     */
    @POST
    @Path("/{projectId}/archive")
    @RequirePermission(value = Perm.PROJECT_ARCHIVE, defaultRole = ProjectRole.OWNER)
    public ProjectView archiveProject(@ProjectId @PathParam("projectId") UUID projectId) {
        return ProjectView.of(projectService.archive(projectId), roleOf(projectId));
    }

    /** Restores an archived project to active status. */
    @POST
    @Path("/{projectId}/unarchive")
    @Transactional
    @RequirePermission(value = Perm.PROJECT_ARCHIVE, defaultRole = ProjectRole.OWNER)
    public ProjectView unarchiveProject(@ProjectId @PathParam("projectId") UUID projectId) {
        return ProjectView.of(projectService.unarchive(projectId), roleOf(projectId));
    }

    /**
     * Transfers project ownership to another member.
     */
    @POST
    @Path("/{projectId}/transfer")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequirePermission(value = Perm.PROJECT_TRANSFER, defaultRole = ProjectRole.OWNER)
    public ProjectMemberView transferProject(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid TransferProjectRequest request) {
        projectService.requireWritable(projectId);
        return ProjectMemberView.of(
                userService.transferOwnership(requireUser().getId(), projectId, request.userId()));
    }

    /** Deletes a project and all associated resources. */
    @DELETE
    @Path("/{projectId}")
    @RequirePermission(value = Perm.PROJECT_DELETE, defaultRole = ProjectRole.OWNER)
    public void deleteProject(@ProjectId @PathParam("projectId") UUID projectId) {
        if (!projectService.delete(projectId)) {
            throw new NotFoundException("no such project: " + projectId);
        }
    }

    /** Sets or updates a project member's role. */
    @PUT
    @Path("/{projectId}/member/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequirePermission(value = Perm.PROJECT_MEMBER_MANAGE, defaultRole = ProjectRole.OWNER)
    public ProjectMemberView setMemberRole(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("userId") UUID userId,
            @NotNull(message = "a request body is required") @Valid SetMemberRoleRequest request) {
        projectService.requireWritable(projectId);
        return ProjectMemberView.of(userService.grant(userId, projectId, request.role()));
    }

    /**
     * Removes a member from a project, or allows a member to leave.
     * Members can remove themselves without requiring manage permissions.
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
        projectService.requireWritable(projectId);
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
