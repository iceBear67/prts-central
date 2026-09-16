package io.ib67.prts.stats;

import io.ib67.prts.dto.HourlyCount;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.stats.StatsService.Completion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsServiceTest {

    private static final Instant FROM = Instant.parse("2026-09-13T09:00:00Z");
    private static final Instant DAY = Instant.parse("2026-06-17T00:00:00Z");

    @Test
    void everyBucketIsPresentAndHourAligned() {
        var buckets = StatsService.hourly(List.of(), FROM, StatsService.WINDOW_HOURS);

        assertEquals(StatsService.WINDOW_HOURS, buckets.size());
        assertEquals(FROM, buckets.getFirst().hour());
        assertEquals(FROM.plus(23, ChronoUnit.HOURS), buckets.getLast().hour());
        assertTrue(buckets.stream().allMatch(
                bucket -> bucket.success() + bucket.failed() + bucket.cancelled() == 0));
    }

    @Test
    void aCompletionLandsInTheHourItStarted() {
        var buckets = StatsService.hourly(
                List.of(
                        new Completion(FROM, JobState.SUCCESS),
                        new Completion(FROM.plus(59, ChronoUnit.MINUTES), JobState.SUCCESS),
                        new Completion(FROM.plus(60, ChronoUnit.MINUTES), JobState.FAILED),
                        new Completion(FROM.plus(23, ChronoUnit.HOURS), JobState.CANCELLED)),
                FROM, StatsService.WINDOW_HOURS);

        assertEquals(2, buckets.getFirst().success());
        assertEquals(1, buckets.get(1).failed());
        assertEquals(1, buckets.getLast().cancelled());
    }

    /** A row outside the window would otherwise index past the array; the query's bound is inclusive. */
    @Test
    void completionsOutsideTheWindowAreDropped() {
        var buckets = StatsService.hourly(
                List.of(
                        new Completion(FROM.minusSeconds(1), JobState.SUCCESS),
                        new Completion(FROM.plus(24, ChronoUnit.HOURS), JobState.SUCCESS)),
                FROM, StatsService.WINDOW_HOURS);

        assertTrue(buckets.stream().allMatch(bucket -> bucket.success() == 0));
    }

    /** PENDING and RUNNING carry no completedAt, so nothing should count them if one leaks through. */
    @Test
    void aNonTerminalStateCountsTowardsNothing() {
        var buckets = StatsService.hourly(
                List.of(new Completion(FROM, JobState.RUNNING)), FROM, StatsService.WINDOW_HOURS);

        var first = buckets.getFirst();
        assertEquals(0, first.success() + first.failed() + first.cancelled());
    }

    /**
     * The wide heatmap asks for the same series over thirty days, so the bucket count is the caller's
     * and nothing in the bucketing may assume the short window.
     */
    @Test
    void theHourlySeriesAlsoCoversAMonthOfHours() {
        var buckets = StatsService.hourly(
                List.of(
                        new Completion(FROM, JobState.SUCCESS),
                        new Completion(FROM.plus(719, ChronoUnit.HOURS), JobState.FAILED),
                        new Completion(FROM.plus(720, ChronoUnit.HOURS), JobState.SUCCESS)),
                FROM, StatsService.WINDOW_HOURS_LONG);

        assertEquals(720, buckets.size());
        assertEquals(1, buckets.getFirst().success());
        assertEquals(1, buckets.getLast().failed());
        assertEquals(FROM.plus(719, ChronoUnit.HOURS), buckets.getLast().hour());
        // The one past the end is dropped rather than folded into the last bucket.
        assertEquals(1, buckets.stream().mapToLong(HourlyCount::success).sum());
    }

    @Test
    void everyDayIsPresentAndNamedInUtc() {
        var buckets = StatsService.daily(List.of(), DAY);

        assertEquals(StatsService.WINDOW_DAYS, buckets.size());
        assertEquals(LocalDate.of(2026, 6, 17), buckets.getFirst().day());
        assertEquals(LocalDate.of(2026, 6, 17).plusDays(89), buckets.getLast().day());
        assertTrue(buckets.stream().allMatch(
                bucket -> bucket.success() + bucket.failed() + bucket.cancelled() == 0));
    }

    /** The heatmap colours a cell by outcome mix, so a day has to hold all three separately. */
    @Test
    void aCompletionLandsInTheUtcDayItFinished() {
        var buckets = StatsService.daily(
                List.of(
                        new Completion(DAY, JobState.SUCCESS),
                        new Completion(DAY.plus(23, ChronoUnit.HOURS), JobState.FAILED),
                        new Completion(DAY.plus(24, ChronoUnit.HOURS), JobState.CANCELLED),
                        new Completion(DAY.plus(89, ChronoUnit.DAYS), JobState.SUCCESS)),
                DAY);

        assertEquals(1, buckets.getFirst().success());
        assertEquals(1, buckets.getFirst().failed());
        assertEquals(1, buckets.get(1).cancelled());
        assertEquals(1, buckets.getLast().success());
    }

    @Test
    void completionsOutsideTheDailyWindowAreDropped() {
        var buckets = StatsService.daily(
                List.of(
                        new Completion(DAY.minusSeconds(1), JobState.SUCCESS),
                        new Completion(DAY.plus(90, ChronoUnit.DAYS), JobState.SUCCESS)),
                DAY);

        assertTrue(buckets.stream().allMatch(bucket -> bucket.success() == 0));
    }
}
