package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminStatsService;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.admin.AdminStatsView;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Administrative endpoint providing system-wide statistics for the dashboard.
 */
@Path("/admin/stats")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminStatsResource {

    @Inject
    AdminStatsService adminStatsService;

    @GET
    public AdminStatsView getStats() {
        return adminStatsService.collect();
    }
}
