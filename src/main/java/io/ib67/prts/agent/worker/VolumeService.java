package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.agent.worker.entity.WorkerEntity;
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
 * Allocates and releases project worker volumes.
 */
@ApplicationScoped
public class VolumeService {
    private static final Logger LOG = Logger.getLogger(VolumeService.class);

    @Inject
    ProjectService projectService;
    @Inject
    WorkerService workerService;

    /**
     * Allocates a volume on a worker selected by the scheduler.
     *
     * <p>Persists the record as {@link VolumeState#PROVISIONING} prior to the RPC,
     * transitioning to {@link VolumeState#READY} on acknowledgment or removing the record on failure.
     */
    public WorkerVolume create(UUID projectId, String name, long sizeBytes) {
        var workerId = workerService.scheduler.selectVolumeHost()
                .orElseThrow(() -> new IllegalStateException("No available worker for this project volume"));
        var volumeId = QuarkusTransaction.requiringNew().call(() -> {
            var worker = WorkerEntity.<WorkerEntity>findByIdOptional(workerId)
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
        workerService.createVolume(workerId, volumeId, projectId, name, sizeBytes)
                .whenComplete((allocated, t) -> {
                    if (t != null) discard(volumeId);
                }).join();
        return QuarkusTransaction.requiringNew().call(() -> {
            var volume = WorkerVolume.<WorkerVolume>findById(volumeId);
            volume.setState(VolumeState.READY);
            return WorkerVolume.findByIdFetched(volumeId).orElse(volume);
        });
    }

    /**
     * Releases a volume and deletes its physical storage on the worker.
     *
     * @throws ClientErrorException with 409 Conflict if still mounted by any task
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
            // Transition to RELEASING so failed RPCs prevent reuse of partially deleted volumes.
            volume.setState(VolumeState.RELEASING);
            return volume.getWorker().getId();
        });
        workerService.deleteVolume(workerId, volumeId).join();
        QuarkusTransaction.requiringNew().run(() -> WorkerVolume.deleteById(volumeId));
    }

    /**
     * Removes a volume row if creation failed or was rejected.
     */
    private void discard(UUID volumeId) {
        try {
            QuarkusTransaction.requiringNew().run(() -> WorkerVolume.deleteById(volumeId));
        } catch (RuntimeException e) {
            LOG.errorf(e, "cannot drop volume %s after the worker refused it", volumeId);
        }
    }
}
