package io.ib67.prts.pending;

/** Lifecycle states of a queued pending job. */
public enum PendingJobState {
    QUEUED,
    /** Claimed by dispatcher; dispatch attempt in flight. */
    DISPATCHING,
    /** Successfully dispatched and converted to a running Job. */
    DISPATCHED,
    CANCELLED,
    /** Expired before a worker could accept it. */
    EXPIRED,
    /** Terminated due to unrecoverable errors. */
    FAILED;

    public boolean isSettled() {
        return this != QUEUED && this != DISPATCHING;
    }
}
