package io.ib67.prts.job.task;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.job.task.entity.TaskState;
import io.ib67.prts.job.task.entity.TaskVolume;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.project.ProjectService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages task scopes: their lifecycle, the volumes they mount, and the values they contribute to jobs.
 */
@ApplicationScoped
public class TaskService {
    private static final Logger LOG = Logger.getLogger(TaskService.class);
    private static final String CLOSE_REASON = "task closed";

    @Inject
    ProjectService projectService;
    @Inject
    JobService jobService;

    public Task require(UUID projectId, UUID taskId) {
        return Task.findInProject(projectId, taskId)
                .orElseThrow(() -> new NotFoundException("no such task in project " + projectId + ": " + taskId));
    }

    /**
     * Resolves a task that still accepts changes — a new job, a volume mount, or a scope edit.
     *
     * @throws ClientErrorException with HTTP 409 Conflict if the task is closing or closed
     */
    public Task requireOpen(UUID projectId, UUID taskId) {
        var task = require(projectId, taskId);
        if (!task.getState().acceptsChanges()) {
            throw new ClientErrorException(
                    "task is " + task.getState() + ": " + taskId, Response.Status.CONFLICT);
        }
        return task;
    }

    @Transactional
    public Task create(
            UUID projectId,
            String name,
            String description,
            String trackedAt,
            TaskScope scope,
            UUID createdBy) {
        var task = Task.builder()
                .project(projectService.require(projectId))
                .name(name)
                .description(description)
                .trackedAt(trackedAt)
                .scope(scope)
                .createdBy(createdBy)
                .build();
        // Flushed so @CreationTimestamp runs: the caller's transaction is still open when it maps the
        // task into a view, and createdAt would otherwise still be null there.
        task.persistAndFlush();
        return task;
    }

    /** Applies the fields the caller supplied; a null argument leaves that field as it was. */
    @Transactional
    public Task update(
            UUID projectId,
            UUID taskId,
            @Nullable String name,
            @Nullable String description,
            @Nullable String trackedAt,
            @Nullable TaskScope scope) {
        var task = requireOpen(projectId, taskId);
        if (name != null) {
            task.setName(name);
        }
        if (description != null) {
            task.setDescription(description);
        }
        if (trackedAt != null) {
            task.setTrackedAt(trackedAt);
        }
        if (scope != null) {
            task.setScope(scope);
        }
        return task;
    }

    /**
     * Closes a task and tears it down: queued jobs are cancelled, running ones are stopped, and the
     * task's volume mounts are dropped.
     *
     * <p>Volumes themselves survive — they belong to the project, may be mounted by other tasks, and are
     * only removed from a worker by an explicit delete.
     *
     * <p>Teardown is not guaranteed to finish here. A job dispatched while work was being stopped leaves
     * the task {@code CLOSING}, and {@link TaskTeardownDispatcher} finishes it on a later sweep.
     */
    public Task close(UUID projectId, UUID taskId) {
        var closing = QuarkusTransaction.requiringNew().call(() -> {
            var task = requireIn(projectId, taskId);
            if (task.getState() == TaskState.OPEN) {
                task.transitionTo(TaskState.CLOSING);
            }
            return task.getState() != TaskState.CLOSED;
        });
        if (closing) {
            teardown(taskId);
        }
        return QuarkusTransaction.requiringNew().call(() -> require(projectId, taskId));
    }

    /**
     * Runs one teardown pass over a closing task.
     *
     * @return true once the task reached {@link TaskState#CLOSED}
     */
    public boolean teardown(UUID taskId) {
        try {
            var cancelled = QuarkusTransaction.requiringNew().call(() -> PendingJob.cancelActiveInTask(taskId));
            if (cancelled > 0) {
                LOG.infof("cancelled %s queued jobs of task %s", cancelled, taskId);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot cancel the queued jobs of task %s", taskId);
            return false;
        }
        jobService.stopOpen(openJobs(taskId), CLOSE_REASON);
        return QuarkusTransaction.requiringNew().call(() -> {
            var task = Task.<Task>findById(taskId, LockModeType.PESSIMISTIC_WRITE);
            if (task == null) {
                return true;
            }
            // A job placed while work was being stopped keeps the task closing until the next sweep.
            if (!Job.listOpenByTask(taskId).isEmpty()) {
                return false;
            }
            TaskVolume.deleteByTask(taskId);
            if (task.getState() != TaskState.CLOSED) {
                task.transitionTo(TaskState.CLOSED);
            }
            return true;
        });
    }

    /**
     * Mounts a project volume into a task, or moves an existing mount to a new path.
     */
    @Transactional
    public TaskVolume attach(UUID projectId, UUID taskId, UUID volumeId, String mountPoint) {
        var task = requireOpen(projectId, taskId);
        var volume = WorkerVolume.findInProject(projectId, volumeId)
                .orElseThrow(() -> new NotFoundException(
                        "no such volume in project " + projectId + ": " + volumeId));
        if (!volume.getState().isUsable()) {
            throw new ClientErrorException(
                    "volume " + volumeId + " is not ready: " + volume.getState(), Response.Status.CONFLICT);
        }
        requireSameHost(taskId, volume);
        requireFreeMountPoint(taskId, volumeId, mountPoint);
        var existing = TaskVolume.findMount(taskId, volumeId);
        if (existing.isPresent()) {
            existing.get().setMountPoint(mountPoint);
            return existing.get();
        }
        var mount = TaskVolume.of(task, volume, mountPoint);
        mount.persist();
        return mount;
    }

    /** Removes a mount. The volume itself is untouched. */
    @Transactional
    public void detach(UUID projectId, UUID taskId, UUID volumeId) {
        requireOpen(projectId, taskId);
        TaskVolume.findMount(taskId, volumeId)
                .orElseThrow(() -> new NotFoundException(
                        "task " + taskId + " does not mount volume " + volumeId))
                .delete();
    }

    public List<TaskVolume> mounts(UUID projectId, UUID taskId) {
        require(projectId, taskId);
        return TaskVolume.listByTaskFetched(taskId);
    }

    /**
     * The volumes a task's jobs mount, in {@link JobSpec} form.
     *
     * <p>The size limit is the volume's whole length: a task shares one volume across its jobs rather
     * than carving a quota out of it per job.
     */
    public Map<UUID, JobSpec.VolumeSpec> mountsOf(UUID taskId) {
        return TaskVolume.listByTaskFetched(taskId).stream()
                .collect(Collectors.toMap(
                        mount -> mount.getVolume().getId(),
                        mount -> new JobSpec.VolumeSpec(mount.getMountPoint(), mount.getVolume().getLength()),
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    /**
     * A task's jobs must all be placeable on one worker, so everything it mounts has to live there.
     */
    private static void requireSameHost(UUID taskId, WorkerVolume volume) {
        var host = TaskVolume.workerOf(taskId);
        if (host.isPresent() && !host.get().equals(volume.getWorker().getId())) {
            throw new ClientErrorException(
                    "task " + taskId + " already mounts volumes on worker " + host.get()
                            + "; a job cannot mount volumes from more than one worker",
                    Response.Status.CONFLICT);
        }
    }

    private static void requireFreeMountPoint(UUID taskId, UUID volumeId, String mountPoint) {
        var taken = TaskVolume.listByTaskFetched(taskId).stream()
                .filter(mount -> mount.getMountPoint().equals(mountPoint))
                .anyMatch(mount -> !mount.getVolume().getId().equals(volumeId));
        if (taken) {
            throw new ClientErrorException(
                    "another volume is already mounted at " + mountPoint, Response.Status.CONFLICT);
        }
    }

    private static Task requireIn(UUID projectId, UUID taskId) {
        var task = Task.<Task>findById(taskId, LockModeType.PESSIMISTIC_WRITE);
        if (task == null || !task.getProject().getId().equals(projectId)) {
            throw new NotFoundException("no such task in project " + projectId + ": " + taskId);
        }
        return task;
    }

    private static List<Job.Open> openJobs(UUID taskId) {
        return QuarkusTransaction.requiringNew().call(() -> Job.listOpenByTask(taskId).stream()
                .map(Job.Open::of)
                .toList());
    }
}
