package io.ib67.prts.job.entity;

/**
 * Lifecycle of a job. Stored on {@link Job} and reported by workers.
 */
public enum JobState {
    /** The job has been created or queued, but is not yet running on a worker. */
    PENDING,
    /** The job is currently executing on a worker. */
    RUNNING,
    /** The job finished unsuccessfully. */
    FAILED,
    /** The job finished successfully. */
    SUCCESS,
    /** The job was cancelled before completion. */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
