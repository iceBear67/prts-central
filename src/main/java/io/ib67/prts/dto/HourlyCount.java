package io.ib67.prts.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Jobs that reached a terminal state within one hour-aligned hour.
 *
 * @param hour Start of the hour the bucket covers.
 */
public record HourlyCount(Instant hour, long success, long failed, long cancelled) {
    /** Bucket width, shared with the code that zero-fills the series. */
    public static final ChronoUnit UNIT = ChronoUnit.HOURS;

    public HourlyCount {
        Objects.requireNonNull(hour, "hour");
    }
}
