package io.ib67.prts.project;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.JobService;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.Project;
import io.ib67.prts.job.entity.ProjectRole;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ProjectService {
    private static final Logger LOG = Logger.getLogger(ProjectService.class);
    private static final String DELETE_REASON = "project deleted";
    private static final String ARCHIVE_REASON = "project archived";
    /** Maximum retry attempts to stop running jobs during project deletion before giving up. */
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
    StorageService storageService;

    public Optional<Project> findById(UUID id) {
        return Project.findByIdOptional(id);
    }

    public Project require(UUID id) {
        return Project.<Project>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such project: " + id));
    }

    /**
     * Ensures the project exists and is not archived.
     *
     * @throws ClientErrorException with HTTP 409 Conflict if the project is archived
     */
    public Project requireWritable(UUID id) {
        var project = require(id);
        if (project.isArchived()) {
            throw new ClientErrorException("project is archived: " + id, Response.Status.CONFLICT);
        }
        return project;
    }

    /** Creates a new project and assigns the specified user as its owner. */
    @Transactional
    public Project create(String name, String description, UUID ownerId) {
        var project = Project.builder().name(name).description(description).build();
        project.persist();
        // Flush so foreign key references to the new project row can succeed.
        Project.flush();
        userService.grant(ownerId, project.getId(), ProjectRole.OWNER);
        return project;
    }

    /** Applies the fields the caller supplied; a null argument leaves that field as it was. */
    @Transactional
    public Project update(UUID id, @Nullable String name, @Nullable String description) {
        var project = require(id);
        if (name != null) {
            project.setName(name);
        }
        if (description != null) {
            project.setDescription(description);
        }
        return project;
    }

    /**
     * Archives a project, cancelling pending queue entries and interrupting running jobs before marking it archived.
     */
    public Project archive(UUID id) {
        require(id);
        stopWork(id, ARCHIVE_REASON);
        return QuarkusTransaction.requiringNew().call(() -> {
            var project = Project.<Project>findById(id, LockModeType.PESSIMISTIC_WRITE);
            if (project == null) {
                throw new NoSuchElementException("no such project: " + id);
            }
            if (!project.isArchived()) {
                project.setArchivedAt(Instant.now());
            }
            return project;
        });
    }

    @Transactional
    public Project unarchive(UUID id) {
        var project = require(id);
        project.setArchivedAt(null);
        return project;
    }

    /**
     * Deletes a project and cleans up all associated resources (running jobs, external storage, and database records).
     *
     * <p>Running worker jobs and storage objects are stopped and removed first to prevent orphaned external resources.
     * If jobs are scheduled concurrently during deletion, the cleanup retries up to {@link #MAX_STOP_ROUNDS} times.
     */
    public boolean delete(UUID id) {
        for (var round = 1; ; round++) {
            stopWork(id, DELETE_REASON);
            deleteObjects(id);
            deleteVolumes(id);
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
     * Cancels queued jobs and interrupts currently running jobs on workers.
     */
    private void stopWork(UUID projectId, String reason) {
        try {
            var cancelled = QuarkusTransaction.requiringNew().call(() -> PendingJob.cancelActive(projectId));
            if (cancelled > 0) {
                LOG.infof("cancelled %s queued jobs of project %s", cancelled, projectId);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot cancel the queued jobs of project %s", projectId);
        }
        jobService.stopOpen(openJobs(projectId), reason);
    }

    /**
     * Tells workers to discard the project's volumes.
     *
     * <p>{@code worker_volume.project_id} cascades, so without this the rows vanish while the data stays
     * on the workers with nothing left pointing at it.
     */
    private void deleteVolumes(UUID projectId) {
        Map<UUID, UUID> hosts;
        try {
            hosts = QuarkusTransaction.requiringNew().call(() -> WorkerVolume.listByProject(projectId).stream()
                    .collect(Collectors.toMap(WorkerVolume::getId, volume -> volume.getWorker().getId())));
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot list the volumes of project %s; they are left on their workers", projectId);
            return;
        }
        hosts.forEach((volumeId, workerId) -> {
            try {
                workerService.deleteVolume(workerId, volumeId);
            } catch (RuntimeException e) {
                LOG.errorf(e, "cannot discard volume %s on worker %s; it is left behind", volumeId, workerId);
            }
        });
    }

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
            LOG.debugf("deleting artifact object %s of project %s", key, projectId);
            try {
                storageService.deleteQuietly(key);
            } catch (RuntimeException e) {
                // Stop attempting further deletions if storage client initialization fails
                LOG.errorf(e, "cannot reach storage; the objects of project %s are left behind", projectId);
                return;
            }
        }
    }

    /**
     * Deletes project database records and associated sub-accounts and permissions in a transaction.
     */
    private Rows deleteRows(UUID id) {
        // Lock the project row to prevent concurrent job insertions
        var project = Project.<Project>findById(id, LockModeType.PESSIMISTIC_WRITE);
        if (project == null) {
            return Rows.ABSENT;
        }
        // Check if any job was started concurrently after stopWork
        if (!Job.listOpenByProject(id).isEmpty()) {
            return Rows.BUSY;
        }
        subAccountService.list(id).forEach(account -> userService.delete(account.getUserId()));
        permissionService.revokeAllInProject(id);
        project.delete();
        // Explicitly flush project removal before running bulk delete on resource_class
        Project.flush();
        ResourceClass.deleteByProject(id);
        return Rows.DELETED;
    }

    private enum Rows { DELETED, ABSENT, BUSY }

    private static List<Job.Open> openJobs(UUID projectId) {
        return QuarkusTransaction.requiringNew().call(() -> Job.listOpenByProject(projectId).stream()
                .map(Job.Open::of)
                .toList());
    }
}
