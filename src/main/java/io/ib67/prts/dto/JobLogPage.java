package io.ib67.prts.dto;

import io.ib67.prts.project.JobLog;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * A window of log lines. {@code length} echoes the requested window, so fewer {@code items} than
 * that means the end has been reached — no total is computed, which would cost a second query.
 */
public record JobLogPage(
        List<JobLogView> items,
        int offset,
        int length
) {
    public record JobLogView(
            Instant createdAt,
            @Nullable String topic,
            @Nullable String message,
            boolean error
    ) {
        public static JobLogView of(JobLog log) {
            return new JobLogView(
                    log.getCreatedAt(), log.getTopic(), log.getMessage(), Boolean.TRUE.equals(log.getError()));
        }
    }

    public static JobLogPage of(List<JobLog> logs, int offset, int length) {
        return new JobLogPage(logs.stream().map(JobLogView::of).toList(), offset, length);
    }
}
