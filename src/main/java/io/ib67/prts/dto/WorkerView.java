package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.Worker;
import jakarta.annotation.Nullable;

import java.util.UUID;

/**
 * Public snapshot of a connected worker. Internal scheduling state and the RPC channel stay off this type.
 */
public record WorkerView(
        UUID id,
        String name,
        @Nullable Worker.Info info
) {
    public static WorkerView of(UUID id, Worker worker) {
        return new WorkerView(id, worker.getName(), worker.getInfo());
    }
}
