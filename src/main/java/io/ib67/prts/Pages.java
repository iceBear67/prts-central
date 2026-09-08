package io.ib67.prts;

import jakarta.annotation.Nullable;

/** Window arithmetic shared by the paged listings. */
public final class Pages {

    private Pages() {
    }

    /** A missing or non-positive length means "as many as allowed". */
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
