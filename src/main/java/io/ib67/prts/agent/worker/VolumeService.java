package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.agent.worker.entity.Worker;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.task.entity.TaskVolume;
import io.ib67.prts.project.ProjectService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Allocates and releases worker volumes on behalf of a project.
 *
 * <p>A volume belongs to its project, not to any task. Tasks mount it, and closing a task only drops
 * the mount — a volume leaves its worker exactly when someone deletes it here.
 */
@ApplicationScoped
public class VolumeService {
    private static final Logger LOG = Logger.getLogger(VolumeService.class);

    @Inject
    ProjectService projectService;
    @Inject
    WorkerService workerService;

    /**
     * Allocates a volume on a worker chosen by the scheduler.
     *
     * <p>The row is committed as {@link VolumeState#PROVISIONING} before the blocking RPC, since a
     * transaction may not span one. A worker that refuses leaves nothing behind: the row is dropped
     * again and the refusal reaches the caller.
     */
    public WorkerVolume create(UUID projectId, String name, long sizeBytes) {
        var workerId = workerService.selectVolumeHost();
        var volumeId = QuarkusTransaction.requiringNew().call(() -> {
            var worker = Worker.<Worker>findByIdOptional(workerId)
                    .orElseThrow(() -> new NoSuchElementException("no such worker: " + workerId));
            var volume = WorkerVolume.builder()
                    .name(name)
                    .project(projectService.require(projectId))
                    .worker(worker)
                    .length(sizeBytes)
                    .state(VolumeState.PROVISIONING)
                    .build();
            volume.persist();
            return volume.getId();
        });
        try {
            workerService.createVolume(workerId, volumeId, projectId, name, sizeBytes);
        } catch (RuntimeException e) {
            discard(volumeId);
            throw e;
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            var volume = WorkerVolume.<WorkerVolume>findById(volumeId);
            volume.setState(VolumeState.READY);
            return WorkerVolume.findByIdFetched(volumeId).orElse(volume);
        });
    }

    /**
     * Discards a volume and everything stored in it.
     *
     * @throws ClientErrorException with HTTP 409 Conflict if any task still mounts it
     */
    public void delete(UUID projectId, UUID volumeId) {
        var workerId = QuarkusTransaction.requiringNew().call(() -> {
            var volume = WorkerVolume.<WorkerVolume>findById(volumeId, LockModeType.PESSIMISTIC_WRITE);
            if (volume == null || !volume.getProject().getId().equals(projectId)) {
                throw new NotFoundException("no such volume in project " + projectId + ": " + volumeId);
            }
            var mounts = TaskVolume.countByVolume(volumeId);
            if (mounts > 0) {
                throw new ClientErrorException(
                        "volume " + volumeId + " is mounted by " + mounts + " task(s); detach it first",
                        Response.Status.CONFLICT);
            }
            // Stays RELEASING if the worker cannot be reached, so a repeated delete resumes rather than
            // handing the volume back out while its data may already be gone.
            volume.setState(VolumeState.RELEASING);
            return volume.getWorker().getId();
        });
        workerService.deleteVolume(workerId, volumeId);
        QuarkusTransaction.requiringNew().run(() -> WorkerVolume.deleteById(volumeId));
    }

    /** Drops a row the worker never acknowledged creating. */
    private void discard(UUID volumeId) {
        try {
            QuarkusTransaction.requiringNew().run(() -> WorkerVolume.deleteById(volumeId));
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot drop volume %s after the worker refused it", volumeId);
        }
    }
}
