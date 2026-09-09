package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.admin.AdminStatsView;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.storage.ArtifactService;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.User;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.time.Instant;

/**
 * Administrative endpoint providing system-wide statistics for the dashboard.
 */
@Path("/admin/stats")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(Perm.ADMIN_OF_ALL)
public class AdminStatsResource {

    private static final Duration RECENT = Duration.ofDays(1);

    @Inject
    WorkerService workerService;
    @Inject
    ArtifactService artifactService;

    @GET
    @Transactional
    public AdminStatsView getStats() {
        var live = workerService.getActiveWorkers().values();
        var artifacts = artifactService.stored();
        return new AdminStatsView(
                new AdminStatsView.Users(User.count(), SubAccount.count()),
                new AdminStatsView.Projects(Project.count(), Project.count("archivedAt is not null")),
                new AdminStatsView.Workers(
                        Worker.count(),
                        Worker.count("disabled", true),
                        live.size(),
                        live.stream().filter(worker -> !worker.isDisabled()).count()),
                new AdminStatsView.Jobs(
                        Job.countByState(), Job.countCompletedSince(Instant.now().minus(RECENT))),
                new AdminStatsView.Queue(PendingJob.countByState()),
                new AdminStatsView.Storage(artifacts.count(), artifacts.bytes()));
    }
}
