package io.ib67.prts.agent.worker.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.ResourceClassView;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource class catalogue, readable by any authenticated caller.
 *
 * <p>Classes are service-wide configuration keyed by name, not per-tenant data, and a member who can
 * submit a job into one can already observe its limits by running something — so a task scope editor
 * can offer a picker instead of free text, and a job page can say what the job was allowed to use.
 * Defining, changing and deleting a class stays under {@code /admin}.
 */
@Path("/resource-class")
@Produces(MediaType.APPLICATION_JSON)
public class ResourceClassResource {

    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public Page<ResourceClassView> listResourceClasses(
            @QueryParam("query") @Nullable String query,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                ResourceClass.search(query, start, window).stream()
                        .map(ResourceClassView::of)
                        .toList(),
                start,
                window,
                ResourceClass.countSearch(query));
    }
}
