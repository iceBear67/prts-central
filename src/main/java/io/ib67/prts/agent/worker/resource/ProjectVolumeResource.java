package io.ib67.prts.agent.worker.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.VolumeService;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.WorkerVolumeView;
import io.ib67.prts.dto.request.CreateVolumeRequest;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.project.ProjectConfig;
import io.ib67.prts.project.ProjectService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * REST endpoint allocating and releasing a project's worker volumes.
 *
 * <p>Volumes belong to the project. Tasks mount them, and closing a task only unmounts — a volume
 * leaves its worker exactly when it is deleted here.
 */
@Path("/project/{projectId}/volume")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectVolumeResource {
    @Inject
    VolumeService volumeService;
    @Inject
    ProjectService projectService;
    @Inject
    ProjectConfig projectConfig;

    @GET
    @Transactional
    @RequirePermission(value = Perm.PROJECT_READ, defaultRole = ProjectRole.VIEWER)
    public List<WorkerVolumeView> listVolumes(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        projectService.require(projectId);
        var window = Pages.clampLength(length, projectConfig.list().maxPageSize());
        return WorkerVolume.listByProject(projectId, Pages.clampOffset(offset, window), window).stream()
                .map(WorkerVolumeView::of)
                .toList();
    }

    /**
     * Allocates a volume on a worker chosen by the scheduler.
     *
     * <p>Blocks until the worker acknowledges. A worker without room to spare refuses, and its reason is
     * returned rather than a row nobody can use.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @RequirePermission(value = Perm.PROJECT_VOLUME_MANAGE, defaultRole = ProjectRole.OWNER)
    public WorkerVolumeView createVolume(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid CreateVolumeRequest request) {
        projectService.requireWritable(projectId);
        return WorkerVolumeView.of(volumeService.create(projectId, request.name(), request.sizeBytes()));
    }

    /** Discards a volume and everything stored in it. Conflicts while any task still mounts it. */
    @DELETE
    @Path("/{volumeId}")
    @RequirePermission(value = Perm.PROJECT_VOLUME_MANAGE, defaultRole = ProjectRole.OWNER)
    public void deleteVolume(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("volumeId") UUID volumeId) {
        projectService.requireWritable(projectId);
        volumeService.delete(projectId, volumeId);
    }
}
