package io.ib67.prts.agent.worker.entity;

/**
 * Provisioning state of a {@link WorkerVolume}.
 */
public enum VolumeState {
    /** Volume allocation requested, awaiting worker acknowledgment. */
    PROVISIONING,
    /** Volume successfully allocated and ready for job mounting. */
    READY,
    /** Volume deletion requested, awaiting worker acknowledgment. */
    RELEASING;

    /** Returns true if the volume is ready to be mounted. */
    public boolean isUsable() {
        return this == READY;
    }
}
