package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.admin.PermissionView;
import io.ib67.prts.user.PermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Arrays;
import java.util.List;

/**
 * The permission catalogue, so a client need not hard-code the list.
 *
 * <p>Bans are read-only here: they come from {@code permission.banned} and changing one means editing
 * the configuration and restarting.
 */
@Path("/admin/permission")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminPermissionResource {

    @Inject
    PermissionService permissionService;

    @GET
    public List<PermissionView> listPermissions() {
        return Arrays.stream(Perm.values())
                .map(perm -> PermissionView.of(perm, permissionService.isBanned(perm)))
                .toList();
    }
}
