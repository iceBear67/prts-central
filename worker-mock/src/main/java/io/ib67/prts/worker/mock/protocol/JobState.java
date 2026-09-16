package io.ib67.prts.worker.mock.protocol;

/**
 * Lifecycle of a job as the wire spells it.
 */
public enum JobState {
    /** Created or queued, not yet running on a worker. */
    PENDING,
    /** Executing on a worker. */
    RUNNING,
    /** Finished unsuccessfully. */
    FAILED,
    /** Finished successfully. */
    SUCCESS,
    /** Cancelled before completion. */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
