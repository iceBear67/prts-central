package io.ib67.prts;

import jakarta.annotation.Nullable;

/** Utility methods for pagination calculations. */
public final class Pages {

    private Pages() {
    }

    /** Returns the clamped page length, defaulting to {@code max} if null or non-positive. */
    public static int clampLength(@Nullable Integer length, int max) {
        if (length == null || length <= 0) {
            return max;
        }
        return Math.min(length, max);
    }

    public static int clampOffset(int offset, int window) {
        return Math.clamp(offset, 0, Integer.MAX_VALUE - window);
    }
}
