package io.ib67.prts.job.task.entity;

/**
 * Lifecycle of a task.
 */
public enum TaskState {
    /** Task is active and accepting new jobs and mounts. */
    OPEN,
    /** Task closure initiated; resources are being torn down. */
    CLOSING,
    /** Teardown complete; task record retained as execution history. */
    CLOSED;

    /** Returns true if teardown has completed. */
    public boolean isClosed() {
        return this == CLOSED;
    }

    /** Returns true if the task accepts job executions, volume attachments, or updates. */
    public boolean acceptsChanges() {
        return this == OPEN;
    }
}
