package io.ib67.prts.testing;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Silences logging categories for the duration of a block.
 *
 * <p>A test that drives a failure path on purpose still runs the handler logging it, and a best-effort
 * handler logs the whole stack trace. Wrap the acting call so the report stays readable.
 */
public final class MutedLogs implements AutoCloseable {

    private final Logger[] loggers;
    private final Level[] restored;

    public MutedLogs(Class<?>... categories) {
        loggers = new Logger[categories.length];
        restored = new Level[categories.length];
        for (var i = 0; i < categories.length; i++) {
            // Kept referenced for the block: an unreferenced JUL logger can be collected, and the
            // level goes with it.
            loggers[i] = Logger.getLogger(categories[i].getName());
            restored[i] = loggers[i].getLevel();
            loggers[i].setLevel(Level.OFF);
        }
    }

    /** Restores each category to its own level, or to null where it inherited one. */
    @Override
    public void close() {
        for (var i = 0; i < loggers.length; i++) {
            loggers[i].setLevel(restored[i]);
        }
    }
}
