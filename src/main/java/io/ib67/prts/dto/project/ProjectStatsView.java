package io.ib67.prts.dto.project;

import io.ib67.prts.dto.Counts;
import io.ib67.prts.dto.DailyCount;
import io.ib67.prts.dto.HourlyCount;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.task.entity.TaskState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One project's own statistics: the admin dashboard's aggregation with a project predicate, behind
 * the project's own read gate rather than {@code admin:all}.
 */
public record ProjectStatsView(Jobs jobs, Tasks tasks, Storage storage) {
    public ProjectStatsView {
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(storage, "storage");
    }

    /**
     * @param byState       Visible job counts grouped by state.
     * @param hourlyLast30d One bucket per hour, oldest first, always 720 entries — the same series
     *                      the admin dashboard draws, for the heatmap at a width a day per cell is
     *                      too coarse for.
     * @param dailyLast90d  One bucket per UTC day, oldest first, always 90 entries.
     */
    public record Jobs(
            Map<JobState, Long> byState,
            List<HourlyCount> hourlyLast30d,
            List<DailyCount> dailyLast90d) {
        public Jobs {
            byState = Counts.filled(JobState.class, Objects.requireNonNull(byState, "byState"));
            Objects.requireNonNull(hourlyLast30d, "hourlyLast30d");
            Objects.requireNonNull(dailyLast90d, "dailyLast90d");
        }
    }

    /** @param byState Task counts grouped by state. */
    public record Tasks(Map<TaskState, Long> byState) {
        public Tasks {
            byState = Counts.filled(TaskState.class, Objects.requireNonNull(byState, "byState"));
        }
    }

    /** @param volumeBytes Bytes the project's volumes reserve. Allocated, not consumed — see TODO.md. */
    public record Storage(long volumes, long volumeBytes) {
    }
}
