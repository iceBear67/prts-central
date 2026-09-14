package io.ib67.prts.testing;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for temporarily suppressing specified logger categories within a try-with-resources block.
 */
public final class MutedLogs implements AutoCloseable {

    private final Logger[] loggers;
    private final Level[] restored;

    public MutedLogs(Class<?>... categories) {
        loggers = new Logger[categories.length];
        restored = new Level[categories.length];
        for (var i = 0; i < categories.length; i++) {
            // Retain references to prevent garbage collection of java.util.logging.Logger instances.
            loggers[i] = Logger.getLogger(categories[i].getName());
            restored[i] = loggers[i].getLevel();
            loggers[i].setLevel(Level.OFF);
        }
    }

    /** Restores original log levels for all suppressed categories. */
    @Override
    public void close() {
        for (var i = 0; i < loggers.length; i++) {
            loggers[i].setLevel(restored[i]);
        }
    }
}
