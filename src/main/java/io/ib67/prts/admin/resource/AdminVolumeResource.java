package io.ib67.prts.admin.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.WorkerVolumeView;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * Administrative listing of worker volumes across every worker and project.
 *
 * <p>{@code /worker/{id}/volume} answers the same question one host at a time.
 */
@Path("/admin/volume")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminVolumeResource {

    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public List<WorkerVolumeView> listVolumes(
            @QueryParam("worker") @Nullable UUID workerId,
            @QueryParam("project") @Nullable UUID projectId,
            @QueryParam("state") @Nullable VolumeState state,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") @Nullable Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        return WorkerVolume.search(
                        workerId, projectId, state, Pages.clampOffset(offset, window), window).stream()
                .map(WorkerVolumeView::of)
                .toList();
    }
}
