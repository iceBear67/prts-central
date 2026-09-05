package io.ib67.prts.project;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.project.entity.Artifact;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobState;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.storage.StorageService;
import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.SubAccountService;
import io.ib67.prts.user.UserService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectService {
    private static final Logger LOG = Logger.getLogger(ProjectService.class);
    private static final String DELETE_REASON = "project deleted";

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
    ArtifactUploadService artifactUploadService;
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
     */
    public boolean delete(UUID id) {
        stopWork(id);
        deleteObjects(id);
        return QuarkusTransaction.requiringNew().call(() -> deleteRows(id));
    }

    /**
     * Cancels the queue first, so nothing new is placed while the rest of the delete runs, then tells
     * each worker its job has ceased to exist, drops the uploads still in flight for it, and marks the
     * job {@link JobState#CANCELLED} — which is what keeps a report arriving between the interrupt and
     * the row delete from being taken as an outcome.
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
            artifactUploadService.discardPendingOf(job.id());
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
            try {
                jobService.applyState(job.id(), JobState.CANCELLED);
            } catch (RuntimeException e) {
                LOG.errorf(e, "cannot cancel job %s of project %s", job.id(), projectId);
            }
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
    private boolean deleteRows(UUID id) {
        if (Project.findById(id) == null) {
            return false;
        }
        subAccountService.list(id).forEach(account -> userService.delete(account.getUserId()));
        permissionService.revokeAllInProject(id);
        Project.deleteById(id);
        // Flushed by hand: the bulk delete below touches only resource_class, so Hibernate would not
        // auto-flush the queued remove above — and job and job_spec_template still reference the rows.
        Project.flush();
        ResourceClass.deleteByProject(id);
        return true;
    }

    private static List<OpenJob> openJobs(UUID projectId) {
        return QuarkusTransaction.requiringNew().call(() -> Job.listByProject(projectId).stream()
                .filter(job -> !job.isCompleted())
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
