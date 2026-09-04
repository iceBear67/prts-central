package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.RegisteredWorker;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Public snapshot of a connected worker. Internal scheduling state and the RPC channel stay off this type.
 */
public record WorkerView(
        UUID id,
        String name,
        @Nullable RegisteredWorker.Info info
) {
    public WorkerView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    public static WorkerView of(UUID id, RegisteredWorker registeredWorker) {
        return new WorkerView(id, registeredWorker.getName(), registeredWorker.getInfo());
    }
}
