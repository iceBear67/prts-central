package io.ib67.prts.job;

import io.ib67.prts.Pages;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.job.JobView;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobState;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.UUID;

/**
 * The caller's jobs across every project they can read, newest first.
 *
 * <p>Every other job listing is scoped to one project, so an activity feed had to fan out one request
 * per project and merge client-side — capping how many projects it could cover. Scope here comes from
 * {@link JobAccess#readableProjects()}, which applies the same {@code job:read} gate the per-project
 * listing does, so this endpoint carries no permission annotation of its own.
 *
 * <p>Create payloads are omitted: {@code JobView.createRequest} is gated per project, and answering it
 * across a page spanning many would mean a permission check each. Fetch the job in its own project to
 * re-run it.
 */
@Path("/job")
@Produces(MediaType.APPLICATION_JSON)
public class JobFeedResource {

    @Inject
    JobService jobService;
    @Inject
    JobAccess jobAccess;
    @Inject
    JobConfig jobConfig;

    @GET
    @Transactional
    public Page<JobView> listJobs(
            @QueryParam("project") @Nullable UUID projectId,
            @QueryParam("state") @Nullable JobState state,
            @QueryParam("worker") @Nullable UUID workerId,
            @QueryParam("since") @Nullable Instant since,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var filter = Job.Filter.builder()
                .projects(jobAccess.readableProjects())
                .project(projectId)
                .state(state)
                .worker(workerId)
                .since(since)
                .build();
        var window = Pages.clampLength(length, jobConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                jobService.viewOf(Job.listVisible(filter, start, window)),
                start,
                window,
                Job.countVisible(filter));
    }
}
