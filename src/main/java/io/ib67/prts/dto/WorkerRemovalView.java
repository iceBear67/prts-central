package io.ib67.prts.dto;

/**
 * What dropping a worker registration took with it.
 *
 * <p>A forced removal abandons storage rather than reclaiming it — the host is gone, so nothing
 * acknowledges the delete. The counts say how much was abandoned; both are zero on the ordinary
 * delete, which refuses while anything is held.
 *
 * @param volumesDropped Volume rows deleted without the worker ever being told.
 * @param jobsFailed     Unfinished jobs marked failed because their host will not report again.
 */
public record WorkerRemovalView(long volumesDropped, long jobsFailed) {
}
