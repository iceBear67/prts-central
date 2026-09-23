package io.ib67.prts.agent.worker;

/**
 * A worker answered {@code Ack(ok = false)}: it looked at the request and declined it, so it holds
 * nothing the request would have created. A timeout or a dropped session says no such thing, which is
 * why a caller that compensates tells the two apart.
 */
final class WorkerRefusedException extends IllegalStateException {
    WorkerRefusedException(String reason) {
        super(reason);
    }
}
