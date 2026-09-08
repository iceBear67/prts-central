package io.ib67.prts.dto.admin;

import io.ib67.prts.pending.PendingJobState;
import io.ib67.prts.project.entity.JobState;

import java.util.Map;
import java.util.Objects;

/** Service-wide counters for the admin dashboard. */
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

    /** @param subAccounts Accounts owned by a project rather than a person; included in {@code total}. */
    public record Users(long total, long subAccounts) {
    }

    public record Projects(long total, long archived) {
    }

    /**
     * @param connected   Workers holding a live WebSocket session right now.
     * @param schedulable Connected workers that are not disabled.
     */
    public record Workers(long registered, long disabled, long connected, long schedulable) {
    }

    /**
     * @param byState        Visible job counts per state; a state with no jobs is absent.
     * @param completedLast24h Jobs that reached a terminal state in the past day.
     */
    public record Jobs(Map<JobState, Long> byState, long completedLast24h) {
        public Jobs {
            Objects.requireNonNull(byState, "byState");
        }
    }

    /** @param byState Queue entry counts per state; a state with no entries is absent. */
    public record Queue(Map<PendingJobState, Long> byState) {
        public Queue {
            Objects.requireNonNull(byState, "byState");
        }
    }

    public record Storage(long artifacts, long bytes) {
    }
}
