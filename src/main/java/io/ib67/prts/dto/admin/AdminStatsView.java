package io.ib67.prts.dto.admin;

import io.ib67.prts.dto.Counts;
import io.ib67.prts.dto.DailyCount;
import io.ib67.prts.dto.HourlyCount;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.task.entity.TaskState;
import io.ib67.prts.pending.PendingJobState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** System-wide statistics for the admin dashboard. */
public record AdminStatsView(
        Users users,
        Projects projects,
        Workers workers,
        Jobs jobs,
        Queue queue,
        Tasks tasks,
        Storage storage,
        SystemInfo system
) {
    public AdminStatsView {
        Objects.requireNonNull(users, "users");
        Objects.requireNonNull(projects, "projects");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(system, "system");
    }

    /** @param subAccounts Sub-accounts scoped to projects (included in {@code total}). */
    public record Users(long total, long subAccounts) {
    }

    public record Projects(long total, long archived) {
    }

    /**
     * @param connected   Workers currently connected via WebSocket.
     * @param schedulable Connected and enabled workers.
     */
    public record Workers(long registered, long disabled, long connected, long schedulable) {
    }

    /**
     * @param byState          Job counts grouped by state.
     * @param completedLast24h Jobs completed within the window {@code hourlyLast24h} covers.
     * @param hourlyLast24h    One bucket per hour, oldest first, always 24 entries.
     * @param hourlyLast30d    The same series widened to 30 days, always 720 entries. Its last 24
     *                         buckets are {@code hourlyLast24h}.
     * @param dailyLast90d     One bucket per UTC day, oldest first, always 90 entries.
     */
    public record Jobs(
            Map<JobState, Long> byState,
            long completedLast24h,
            List<HourlyCount> hourlyLast24h,
            List<HourlyCount> hourlyLast30d,
            List<DailyCount> dailyLast90d) {
        public Jobs {
            byState = filled(JobState.class, Objects.requireNonNull(byState, "byState"));
            Objects.requireNonNull(hourlyLast24h, "hourlyLast24h");
            Objects.requireNonNull(hourlyLast30d, "hourlyLast30d");
            Objects.requireNonNull(dailyLast90d, "dailyLast90d");
        }
    }

    /** @param byState Queue entry counts grouped by state. */
    public record Queue(Map<PendingJobState, Long> byState) {
        public Queue {
            byState = filled(PendingJobState.class, Objects.requireNonNull(byState, "byState"));
        }
    }

    /** @param byState Task counts grouped by state. */
    public record Tasks(Map<TaskState, Long> byState) {
        public Tasks {
            byState = filled(TaskState.class, Objects.requireNonNull(byState, "byState"));
        }
    }

    /**
     * @param volumes     Worker volumes allocated across every project.
     * @param volumeBytes Bytes those volumes reserve. Allocated, not consumed — see TODO.md.
     */
    public record Storage(long artifacts, long bytes, long volumes, long volumeBytes) {
    }

    /**
     * @param startedAt   When this process started; uptime is {@code now - startedAt}.
     * @param environment The active configuration profile.
     */
    public record SystemInfo(Instant startedAt, String version, String environment) {
        public SystemInfo {
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(environment, "environment");
        }
    }

    private static <E extends Enum<E>> Map<E, Long> filled(Class<E> type, Map<E, Long> counts) {
        return Counts.filled(type, counts);
    }
}
