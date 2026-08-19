package io.ib67.prts.project;

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
    SUCCESS;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}
