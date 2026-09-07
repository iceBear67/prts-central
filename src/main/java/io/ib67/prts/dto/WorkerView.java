package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.RegisteredWorker;
import io.ib67.prts.agent.worker.entity.Worker;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * A worker as the roster knows it, with the live session's snapshot when it is connected. Internal
 * scheduling state and the RPC channel stay off this type.
 *
 * @param disabled  held back from new jobs, whether or not it is connected.
 * @param connected has a live session right now; {@code info} is null without one.
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
