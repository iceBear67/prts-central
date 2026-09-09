package io.ib67.prts.agent.worker.entity;

/**
 * Provisioning state of a {@link WorkerVolume}.
 *
 * <p>The row is committed before the blocking {@code CreateVolume} RPC runs, so a volume the worker
 * has not acknowledged yet must be distinguishable from one it has.
 */
public enum VolumeState {
    /** The row exists but the worker has not acknowledged creating it. */
    PROVISIONING,
    /** The worker holds the volume; it may be mounted and scheduled onto. */
    READY,
    /** A delete was issued; the row is removed once the worker acknowledges. */
    RELEASING;

    /** Whether jobs may mount this volume. */
    public boolean isUsable() {
        return this == READY;
    }
}
