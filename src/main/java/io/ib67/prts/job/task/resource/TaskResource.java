package io.ib67.prts.job.task.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
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
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.user.UserContext;
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
 * REST endpoint managing task scopes and the volumes they mount.
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

    @GET
    @Transactional
    @RequirePermission(value = Perm.TASK_READ, defaultRole = ProjectRole.VIEWER)
    public List<TaskView> listTasks(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        projectService.require(projectId);
        var window = Pages.clampLength(length, jobConfig.task().maxPageSize());
        return Task.listByProject(projectId, Pages.clampOffset(offset, window), window).stream()
                .map(TaskView::of)
                .toList();
    }

    @GET
    @Path("/{taskId}")
    @Transactional
    @RequirePermission(value = Perm.TASK_READ, defaultRole = ProjectRole.VIEWER)
    public TaskDetailView getTask(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("taskId") UUID taskId) {
        return TaskDetailView.of(taskService.require(projectId, taskId), taskService.mounts(projectId, taskId));
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
        return TaskView.of(taskService.create(
                projectId, request.name(), request.scopeOrEmpty(), userContext.require().getId()));
    }

    /**
     * Edits a task. Only jobs created afterwards see the change.
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
        return TaskView.of(taskService.update(projectId, taskId, request.name(), request.scope()));
    }

    /**
     * Closes a task: its queued jobs are cancelled, its running jobs stopped, and its mounts dropped.
     *
     * <p>The volumes survive — they belong to the project and are only removed from a worker by
     * {@code DELETE /project/{projectId}/volume/{volumeId}}. The task itself is kept as a record of the
     * work it scoped, so this responds with the task rather than 204.
     */
    @DELETE
    @Path("/{taskId}")
    @RequirePermission(value = Perm.TASK_MANAGE, defaultRole = ProjectRole.MEMBER)
    public TaskView closeTask(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("taskId") UUID taskId) {
        projectService.requireWritable(projectId);
        return TaskView.of(taskService.close(projectId, taskId));
    }

    /** Mounts a project volume into the task, or moves an existing mount to a new path. */
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

    /** Unmounts a volume from the task. The volume itself is untouched. */
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
