package io.ib67.prts.worker.mock;

import io.ib67.prts.worker.mock.protocol.Outbound;

/**
 * Where a mock worker's messages go.
 *
 * <p>Split out from {@link MockWorker} so the behaviour can be exercised — by its own tests, and by
 * tests that never open a socket — without a live control plane.
 */
public interface Sink extends AutoCloseable {

    /** Sends one already-encoded message. */
    void send(String text);

    boolean isOpen();

    /** Closes politely, which tells the control plane this worker is going away. */
    void close();

    /** Drops the connection without a close frame. */
    void abort();
}
