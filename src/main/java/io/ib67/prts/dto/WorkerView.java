package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.RegisteredWorker;
import io.ib67.prts.agent.worker.entity.Worker;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * View representing worker registration and current connection status.
 *
 * @param disabled  Whether the worker is paused from receiving new jobs.
 * @param connected Whether the worker currently maintains an active WebSocket session.
 */
public record WorkerView(
        UUID id,
        String name,
        boolean disabled,
        boolean connected,
        @Nullable RegisteredWorker.Info info
) {
    public WorkerView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    public static WorkerView of(Worker row, @Nullable RegisteredWorker live) {
        return new WorkerView(row.getId(), row.getName(), row.isDisabled(), live != null,
                live == null ? null : live.getInfo());
    }
}
