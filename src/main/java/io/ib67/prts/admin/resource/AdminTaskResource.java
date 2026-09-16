package io.ib67.prts.admin.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.admin.AdminConfig;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.task.TaskView;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskState;
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
 * Administrative listing of tasks across every project.
 */
@Path("/admin/task")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminTaskResource {

    @Inject
    AdminConfig adminConfig;

    @GET
    @Transactional
    public Page<TaskView> listTasks(
            @QueryParam("query") @Nullable String query,
            @QueryParam("state") @Nullable TaskState state,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") @Nullable Integer length) {
        var window = Pages.clampLength(length, adminConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                TaskView.of(Task.search(null, query, state, start, window)),
                start,
                window,
                Task.countSearch(null, query, state));
    }
}
