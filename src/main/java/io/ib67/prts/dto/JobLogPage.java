package io.ib67.prts.dto;

import io.ib67.prts.project.JobLog;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.List;

public record JobLogPage(
        List<JobLogView> items,
        int page,
        int size,
        long total
) {
    public record JobLogView(
            long id,
            Instant createdAt,
            @Nullable String topic,
            @Nullable String message,
            @Nullable Boolean error
    ) {
        public static JobLogView of(JobLog log) {
            return new JobLogView(log.getId(), log.getCreatedAt(), log.getTopic(), log.getMessage(), log.getError());
        }
    }

    public static JobLogPage of(List<JobLog> logs, int page, int size, long total) {
        return new JobLogPage(logs.stream().map(JobLogView::of).toList(), page, size, total);
    }
}
