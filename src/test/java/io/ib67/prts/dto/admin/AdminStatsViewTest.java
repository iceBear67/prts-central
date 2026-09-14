package io.ib67.prts.dto.admin;

import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.task.entity.TaskState;
import io.ib67.prts.pending.PendingJobState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminStatsViewTest {

    @Test
    void aJobGroupingCarriesEveryStateEvenWhenTheQueryReturnedNone() {
        var jobs = new AdminStatsView.Jobs(Map.of(JobState.SUCCESS, 3L), 3, List.of());

        assertEquals(JobState.values().length, jobs.byState().size());
        assertEquals(3L, jobs.byState().get(JobState.SUCCESS));
        assertEquals(0L, jobs.byState().get(JobState.CANCELLED));
    }

    @Test
    void anEmptyGroupingIsAllZeroesRatherThanEmpty() {
        assertEquals(
                Map.of(TaskState.OPEN, 0L, TaskState.CLOSING, 0L, TaskState.CLOSED, 0L),
                new AdminStatsView.Tasks(Map.of()).byState());
    }

    /** Ordinal order, so a client rendering the breakdown gets the states in declaration order. */
    @Test
    void aGroupingIsOrderedByDeclaration() {
        var queue = new AdminStatsView.Queue(Map.of(PendingJobState.EXPIRED, 1L));

        assertEquals(List.of(PendingJobState.values()), List.copyOf(queue.byState().keySet()));
    }
}
