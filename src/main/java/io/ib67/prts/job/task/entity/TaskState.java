package io.ib67.prts.job.task.entity;

/**
 * Lifecycle of a task.
 */
public enum TaskState {
    /** The task accepts new jobs and its scope is applied to them. */
    OPEN,
    /** The task was closed and is being torn down; new jobs are refused. */
    CLOSING,
    /** Teardown finished. The task is kept as a record of the work it scoped. */
    CLOSED;

    /** Note this is not the negation of {@link #acceptsChanges()}: a {@code CLOSING} task is neither. */
    public boolean isClosed() {
        return this == CLOSED;
    }

    /**
     * Whether the task still takes new jobs, volume mounts and scope edits.
     *
     * <p>False from the moment it starts closing: teardown drops the mounts and stops the jobs, and
     * would never converge if either could still be added to underneath it.
     */
    public boolean acceptsChanges() {
        return this == OPEN;
    }
}
