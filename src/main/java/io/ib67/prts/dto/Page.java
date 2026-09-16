package io.ib67.prts.dto;

import java.util.List;
import java.util.Objects;

/**
 * One window of a listing, together with how much there is behind it.
 *
 * <p>A bare array cannot tell a page the server truncated from a page that is simply full, so the
 * only proof a listing had ended was one that came back empty. {@code length} echoes the
 * <em>clamped</em> window rather than what the caller asked for, which is what publishes the
 * configured maximum page size.
 *
 * @param offset Index of the first item, after clamping.
 * @param length Size of the window, after clamping to the endpoint's configured maximum.
 * @param total  Rows the listing would return without a window.
 */
public record Page<T>(List<T> items, int offset, int length, long total) {
    public Page {
        Objects.requireNonNull(items, "items");
    }
}
