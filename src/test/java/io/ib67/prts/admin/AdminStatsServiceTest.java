package io.ib67.prts.admin;

import io.ib67.prts.admin.AdminStatsService.Completion;
import io.ib67.prts.job.entity.JobState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminStatsServiceTest {

    private static final Instant FROM = Instant.parse("2026-09-13T09:00:00Z");

    @Test
    void everyBucketIsPresentAndHourAligned() {
        var buckets = AdminStatsService.bucket(List.of(), FROM);

        assertEquals(AdminStatsService.WINDOW_HOURS, buckets.size());
        assertEquals(FROM, buckets.getFirst().hour());
        assertEquals(FROM.plus(23, ChronoUnit.HOURS), buckets.getLast().hour());
        assertTrue(buckets.stream().allMatch(
                bucket -> bucket.success() + bucket.failed() + bucket.cancelled() == 0));
    }

    @Test
    void aCompletionLandsInTheHourItStarted() {
        var buckets = AdminStatsService.bucket(
                List.of(
                        new Completion(FROM, JobState.SUCCESS),
                        new Completion(FROM.plus(59, ChronoUnit.MINUTES), JobState.SUCCESS),
                        new Completion(FROM.plus(60, ChronoUnit.MINUTES), JobState.FAILED),
                        new Completion(FROM.plus(23, ChronoUnit.HOURS), JobState.CANCELLED)),
                FROM);

        assertEquals(2, buckets.getFirst().success());
        assertEquals(1, buckets.get(1).failed());
        assertEquals(1, buckets.getLast().cancelled());
    }

    /** A row outside the window would otherwise index past the array; the query's bound is inclusive. */
    @Test
    void completionsOutsideTheWindowAreDropped() {
        var buckets = AdminStatsService.bucket(
                List.of(
                        new Completion(FROM.minusSeconds(1), JobState.SUCCESS),
                        new Completion(FROM.plus(24, ChronoUnit.HOURS), JobState.SUCCESS)),
                FROM);

        assertTrue(buckets.stream().allMatch(bucket -> bucket.success() == 0));
    }

    /** PENDING and RUNNING carry no completedAt, so nothing should count them if one leaks through. */
    @Test
    void aNonTerminalStateCountsTowardsNothing() {
        var buckets = AdminStatsService.bucket(
                List.of(new Completion(FROM, JobState.RUNNING)), FROM);

        var first = buckets.getFirst();
        assertEquals(0, first.success() + first.failed() + first.cancelled());
    }
}
