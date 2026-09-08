package io.ib67.prts.admin;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.dto.admin.AdminStatsView;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Assembles the service-wide counters behind the admin dashboard.
 */
@ApplicationScoped
public class AdminStatsService {

    private static final Duration RECENT = Duration.ofDays(1);

    @Inject
    EntityManager entityManager;
    @Inject
    WorkerService workerService;

    @Transactional
    public AdminStatsView collect() {
        var live = workerService.getActiveWorkers().values();
        // sum() is null while nothing is stored; count() over the same rows is not.
        var artifacts = (Object[]) entityManager
                .createQuery("select count(a), sum(a.sizeBytes) from Artifact a")
                .getSingleResult();
        var storedBytes = (Long) artifacts[1];
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
                new AdminStatsView.Storage((long) artifacts[0], storedBytes == null ? 0 : storedBytes));
    }
}
