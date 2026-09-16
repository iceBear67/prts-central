package io.ib67.prts.job;

import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.pending.PendingJobState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a merged job listing can be filtered by. */
class JobStatusTest {

    /**
     * The union is written out by hand, so a constant added to either enum has to be added here as
     * well — one this does not name is a state the listing cannot be filtered by at all.
     */
    @Test
    void everyJobStateIsNamed() {
        assertEquals(names(JobState.values()), selected(JobStatus::job));
    }

    @Test
    void everyQueueStateIsNamed() {
        assertEquals(names(PendingJobState.values()), selected(JobStatus::queued));
    }

    /** A member is named after what it selects, and selects something in at least one table. */
    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void aMemberIsNamedAfterWhatItSelects(JobStatus status) {
        assertTrue(status.job() != null || status.queued() != null, status + " selects nothing");
        if (status.job() != null) {
            assertEquals(status.name(), status.job().name());
        }
        if (status.queued() != null) {
            assertEquals(status.name(), status.queued().name());
        }
    }

    private static Set<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).collect(Collectors.toSet());
    }

    private static Set<String> selected(Function<JobStatus, Enum<?>> half) {
        return Arrays.stream(JobStatus.values())
                .filter(status -> half.apply(status) != null)
                .map(Enum::name)
                .collect(Collectors.toSet());
    }
}
