package io.ib67.prts.admin.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.admin.AdminArtifactView;
import io.ib67.prts.job.entity.Artifact;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * Administrative listing of stored artifacts across every project, newest first.
 *
 * <p>Downloads still go through {@code GET /project/{projectId}/job/artifact/{id}}, which is where the
 * presigned URL and the project's own read permission live.
 */
@Path("/admin/artifact")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminArtifactResource {

    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public Page<AdminArtifactView> listArtifacts(
            @QueryParam("project") @Nullable UUID projectId,
            @QueryParam("job") @Nullable UUID jobId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") @Nullable Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                Artifact.search(projectId, jobId, start, window).stream()
                        .map(AdminArtifactView::of)
                        .toList(),
                start,
                window,
                Artifact.countSearch(projectId, jobId));
    }
}
