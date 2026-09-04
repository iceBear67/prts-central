package io.ib67.prts.project.entity;

/**
 * Lifecycle of a job. Stored on {@link Job} and reported by workers.
 */
public enum JobState {
    /** Pod has been created but has not been assigned resources yet. */
    PENDING,
    /** The job is executing. */
    RUNNING,
    /** The job finished unsuccessfully. */
    FAILED,
    /** The job finished successfully. */
    SUCCESS,
    /** The job was cancelled before it could finish on its own. */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
