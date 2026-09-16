package io.ib67.prts.job.task.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpecOverridePermissions;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.request.AttachVolumeRequest;
import io.ib67.prts.dto.request.CreateTaskRequest;
import io.ib67.prts.dto.request.UpdateTaskRequest;
import io.ib67.prts.dto.task.TaskDetailView;
import io.ib67.prts.dto.task.TaskView;
import io.ib67.prts.dto.task.TaskVolumeView;
import io.ib67.prts.job.JobConfig;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.job.task.TaskService;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskState;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.user.UserContext;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for managing tasks, execution scopes, and task volume mounts.
 */
@Path("/project/{projectId}/task")
@Produces(MediaType.APPLICATION_JSON)
public class TaskResource {
    @Inject
    TaskService taskService;
    @Inject
    ProjectService projectService;
    @Inject
    UserContext userContext;
    @Inject
    JobConfig jobConfig;
    @Inject
    JobSpecOverridePermissions overridePermissions;

    @GET
    @Transactional
    @RequirePermission(value = Perm.TASK_READ, defaultRole = ProjectRole.VIEWER)
    public Page<TaskView> listTasks(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("query") @Nullable String query,
            @QueryParam("state") @Nullable TaskState state,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        projectService.require(projectId);
        var window = Pages.clampLength(length, jobConfig.task().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                TaskView.of(Task.search(projectId, query, state, start, window)),
                start,
                window,
                Task.countSearch(projectId, query, state));
    }

    @GET
    @Path("/{taskId}")
    @Transactional
    @RequirePermission(value = Perm.TASK_READ, defaultRole = ProjectRole.VIEWER)
    public TaskDetailView getTask(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("taskId") UUID taskId) {
        var task = taskService.require(projectId, taskId);
        return TaskDetailView.of(task, taskService.mounts(projectId, taskId));
    }

    /** Lists the volumes this task mounts, and where. */
    @GET
    @Path("/{taskId}/volume")
    @Transactional
    @RequirePermission(value = Perm.TASK_READ, defaultRole = ProjectRole.VIEWER)
    public List<TaskVolumeView> listTaskVolumes(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("taskId") UUID taskId) {
        return taskService.mounts(projectId, taskId).stream().map(TaskVolumeView::of).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @Transactional
    @RequirePermission(value = Perm.TASK_MANAGE, defaultRole = ProjectRole.MEMBER)
    public TaskView createTask(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid CreateTaskRequest request) {
        projectService.requireWritable(projectId);
        request.scopeOrEmpty().authorize(overridePermissions);
        return TaskView.of(taskService.create(
                projectId,
                request.name(),
                request.description(),
                request.trackedAt(),
                request.scopeOrEmpty(),
                userContext.require().getId()));
    }

    /**
     * Updates an existing task.
     */
    @PATCH
    @Path("/{taskId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequirePermission(value = Perm.TASK_MANAGE, defaultRole = ProjectRole.MEMBER)
    public TaskView updateTask(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("taskId") UUID taskId,
            @NotNull(message = "a request body is required") @Valid UpdateTaskRequest request) {
        projectService.requireWritable(projectId);
        if (request.scope() != null) {
            request.scope().authorize(overridePermissions);
        }
        return TaskView.of(taskService.update(
                projectId, taskId, request.name(), request.description(), request.trackedAt(), request.scope()));
    }

    /**
     * Closes a task, cancelling its queued jobs, interrupting active jobs, and unmounting volumes.
     */
    @DELETE
    @Path("/{taskId}")
    @RequirePermission(value = Perm.TASK_MANAGE, defaultRole = ProjectRole.MEMBER)
    public TaskView closeTask(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("taskId") UUID taskId) {
        projectService.requireWritable(projectId);
        return TaskView.of(taskService.close(projectId, taskId));
    }

    /** Mounts a project volume into the task, or updates an existing mount path. */
    @PUT
    @Path("/{taskId}/volume/{volumeId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequirePermission(value = Perm.TASK_MANAGE, defaultRole = ProjectRole.MEMBER)
    public TaskVolumeView attachVolume(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("taskId") UUID taskId,
            @PathParam("volumeId") UUID volumeId,
            @NotNull(message = "a request body is required") @Valid AttachVolumeRequest request) {
        projectService.requireWritable(projectId);
        return TaskVolumeView.of(taskService.attach(projectId, taskId, volumeId, request.mountPoint()));
    }

    /** Unmounts a volume from the task. */
    @DELETE
    @Path("/{taskId}/volume/{volumeId}")
    @RequirePermission(value = Perm.TASK_MANAGE, defaultRole = ProjectRole.MEMBER)
    public void detachVolume(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("taskId") UUID taskId,
            @PathParam("volumeId") UUID volumeId) {
        projectService.requireWritable(projectId);
        taskService.detach(projectId, taskId, volumeId);
    }
}
