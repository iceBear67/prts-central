package io.ib67.prts.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Jobs that reached a terminal state within one UTC day.
 *
 * @param day The day the bucket covers, in UTC.
 */
public record DailyCount(LocalDate day, long success, long failed, long cancelled) {
    /** Bucket width, shared with the code that zero-fills the series. */
    public static final ChronoUnit UNIT = ChronoUnit.DAYS;

    public DailyCount {
        Objects.requireNonNull(day, "day");
    }
}
