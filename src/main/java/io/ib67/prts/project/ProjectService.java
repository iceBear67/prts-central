package io.ib67.prts.project;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.project.entity.Artifact;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobState;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.storage.ArtifactService;
import io.ib67.prts.storage.StorageService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.SubAccountService;
import io.ib67.prts.user.UserService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {
    private static final Logger LOG = Logger.getLogger(ProjectService.class);
    private static final String DELETE_REASON = "project deleted";
    /** How often {@link #delete} stops work again before a project that keeps receiving jobs is refused. */
    private static final int MAX_STOP_ROUNDS = 3;

    @Inject
    SubAccountService subAccountService;
    @Inject
    UserService userService;
    @Inject
    PermissionService permissionService;
    @Inject
    JobService jobService;
    @Inject
    WorkerService workerService;
    @Inject
    ArtifactService artifactService;
    @Inject
    StorageService storageService;

    public Optional<Project> findById(UUID id) {
        return Project.findByIdOptional(id);
    }

    public List<Project> listAll() {
        return Project.listAll();
    }

    public Project require(UUID id) {
        return Project.<Project>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such project: " + id));
    }

    @Transactional
    public Project create(String name) {
        var project = Project.builder().name(name).build();
        project.persist();
        return project;
    }

    @Transactional
    public Project rename(UUID id, String name) {
        var project = require(id);
        project.setName(name);
        return project;
    }

    /**
     * Outside-in, because a project owns things that are not rows — containers running on workers,
     * objects in S3 — and no foreign key reaches them. Deleting the rows first would leave a container
     * running for a project that no longer exists, reporting against a job id that resolves to nothing,
     * and orphan every object it had already uploaded.
     *
     * <p>Everything before {@link #deleteRows} is best-effort and logged, and none of it runs in a
     * transaction — the same rule that keeps the blocking worker RPC outside one. A failure part-way
     * therefore leaves orphaned <em>objects</em>, whose keys are in the log, rather than rows nothing
     * can reach.
     *
     * <p>A dispatch attempt already in flight when the queue is cancelled cannot be called back, and
     * may place a job after {@link #stopWork} has read them — so the row half refuses while a job is
     * open, and the work is stopped again. Bounded: a project that keeps receiving jobs is a conflict,
     * not a loop.
     */
    public boolean delete(UUID id) {
        for (var round = 1; ; round++) {
            stopWork(id);
            deleteObjects(id);
            var rows = QuarkusTransaction.requiringNew().call(() -> deleteRows(id));
            if (rows != Rows.BUSY) {
                return rows == Rows.DELETED;
            }
            if (round == MAX_STOP_ROUNDS) {
                throw new ClientErrorException(
                        "project " + id + " keeps receiving jobs; retry the delete", Response.Status.CONFLICT);
            }
            LOG.infof("a job was placed on project %s while it was being deleted; stopping work again", id);
        }
    }

    /**
     * Cancels the queue first, so nothing new is placed while the rest of the delete runs. Then, per
     * open job: marks it {@link JobState#CANCELLED} — from here on a worker's report is no outcome and
     * {@code lockAssignedOpen} refuses it a new upload — tells the worker the job has ceased to exist,
     * and only then drops the uploads still in flight, when nothing is left to start another.
     */
    private void stopWork(UUID projectId) {
        try {
            var cancelled = QuarkusTransaction.requiringNew().call(() -> PendingJob.cancelActive(projectId));
            if (cancelled > 0) {
                LOG.infof("cancelled %s queued jobs of project %s", cancelled, projectId);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot cancel the queued jobs of project %s", projectId);
        }
        for (var job : openJobs(projectId)) {
            try {
                jobService.applyState(job.id(), JobState.CANCELLED);
            } catch (RuntimeException e) {
                LOG.errorf(e, "cannot cancel job %s of project %s", job.id(), projectId);
            }
            if (job.worker() != null) {
                try {
                    if (!workerService.interrupt(job.worker(), job.id(), DELETE_REASON)) {
                        LOG.warnf("worker %s is not connected: job %s may still be running there",
                                job.worker(), job.id());
                    }
                } catch (RuntimeException e) {
                    LOG.errorf(e, "cannot interrupt job %s on worker %s", job.id(), job.worker());
                }
            }
            artifactService.discardPendingOf(job.id());
        }
    }

    /** Keys only: the rows are about to go, and reading them back after the delete would be too late. */
    private void deleteObjects(UUID projectId) {
        List<String> keys;
        try {
            keys = QuarkusTransaction.requiringNew()
                    .call(() -> Artifact.listObjectKeysByProject(projectId));
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot list the artifacts of project %s; its objects are left behind", projectId);
            return;
        }
        for (var key : keys) {
            // Already quiet about a failure, which is why each key is logged: that log is the only
            // record of an object the delete did not reach.
            LOG.debugf("deleting artifact object %s of project %s", key, projectId);
            try {
                storageService.deleteQuietly(key);
            } catch (RuntimeException e) {
                // deleteQuietly swallows an S3 error, but not a storage layer that cannot start at
                // all: that throws creating the client, before the method body. Retrying per key
                // would fail the same way, so give up on the objects — the rows still go, which is
                // the point of doing them last.
                LOG.errorf(e, "cannot reach storage; the objects of project %s are left behind", projectId);
                return;
            }
        }
    }

    /**
     * The row half. Jobs, logs, artifacts, templates, volumes, secrets, locks, the queue and the roster
     * all go by foreign key; what is left is the two things keyed on the project without one, and the
     * sub-accounts, whose {@code prts_user} rows are project property and would outlive the cascade
     * that takes their link.
     */
    private Rows deleteRows(UUID id) {
        // FOR UPDATE, and not against another delete: inserting a job takes FOR KEY SHARE on its
        // project row, so an attempt that is persisting one has either committed — and its job is
        // open below — or waits here and fails its FK once the row is gone.
        var project = Project.<Project>findById(id, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) {
            return Rows.ABSENT;
        }
        // stopWork read the jobs before this lock; anything open now was placed since, and may have a
        // container nobody has told to stop.
        if (!Job.listOpenByProject(id).isEmpty()) {
            return Rows.BUSY;
        }
        subAccountService.list(id).forEach(account -> userService.delete(account.getUserId()));
        permissionService.revokeAllInProject(id);
        project.delete();
        // Flushed by hand: the bulk delete below touches only resource_class, so Hibernate would not
        // auto-flush the queued remove above — and job and job_spec_template still reference the rows.
        Project.flush();
        ResourceClass.deleteByProject(id);
        return Rows.DELETED;
    }

    private enum Rows { DELETED, ABSENT, BUSY }

    private static List<OpenJob> openJobs(UUID projectId) {
        return QuarkusTransaction.requiringNew().call(() -> Job.listOpenByProject(projectId).stream()
                .map(job -> new OpenJob(job.getId(), job.getWorker()))
                .toList());
    }

    /**
     * A job to stop, detached: the entities do not outlive the read, and the interrupt that follows
     * must not run inside a transaction.
     *
     * @param worker who to tell, or {@code null} if it was never dispatched.
     */
    private record OpenJob(UUID id, @Nullable UUID worker) {
    }
}
