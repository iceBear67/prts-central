package io.ib67.prts.dto.job;

import io.ib67.prts.job.entity.JobLog;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.Objects;

/** One line of a job's execution log. */
public record JobLogView(
        Instant createdAt,
        @Nullable String topic,
        @Nullable String message,
        boolean error
) {
    public JobLogView {
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static JobLogView of(JobLog log) {
        return new JobLogView(
                log.getCreatedAt(), log.getTopic(), log.getMessage(), Boolean.TRUE.equals(log.getError()));
    }
}
