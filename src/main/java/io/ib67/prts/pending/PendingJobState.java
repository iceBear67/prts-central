package io.ib67.prts.pending;

/** Where a queued create request stands. Everything but the first two is settled for good. */
public enum PendingJobState {
    QUEUED,
    /** Claimed by the dispatcher, with an attempt in flight; nothing else may touch it. */
    DISPATCHING,
    /** A job was created and taken by a worker; the entry names it and stops here. */
    DISPATCHED,
    CANCELLED,
    /** Outlived its authorization before any worker could take it. */
    EXPIRED,
    /** The request itself stopped working — a deleted template, a removed volume, a lost hand-over. */
    FAILED;

    public boolean isSettled() {
        return this != QUEUED && this != DISPATCHING;
    }
}
