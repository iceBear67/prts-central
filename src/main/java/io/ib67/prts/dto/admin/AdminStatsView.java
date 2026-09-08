package io.ib67.prts.dto.admin;

import io.ib67.prts.pending.PendingJobState;
import io.ib67.prts.project.entity.JobState;

import java.util.Map;
import java.util.Objects;

/** System-wide statistics for the admin dashboard. */
public record AdminStatsView(
        Users users,
        Projects projects,
        Workers workers,
        Jobs jobs,
        Queue queue,
        Storage storage
) {
    public AdminStatsView {
        Objects.requireNonNull(users, "users");
        Objects.requireNonNull(projects, "projects");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(storage, "storage");
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
     * @param byState        Job counts grouped by state.
     * @param completedLast24h Jobs completed within the last 24 hours.
     */
    public record Jobs(Map<JobState, Long> byState, long completedLast24h) {
        public Jobs {
            Objects.requireNonNull(byState, "byState");
        }
    }

    /** @param byState Queue entry counts grouped by state. */
    public record Queue(Map<PendingJobState, Long> byState) {
        public Queue {
            Objects.requireNonNull(byState, "byState");
        }
    }

    public record Storage(long artifacts, long bytes) {
    }
}
