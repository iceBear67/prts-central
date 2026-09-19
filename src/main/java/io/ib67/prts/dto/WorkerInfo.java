package io.ib67.prts.dto;

import io.ib67.prts.agent.worker.entity.WorkerEntity;
import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * The worker a view refers to, named rather than left as a bare ID.
 *
 * <p>Every {@code /worker} endpoint is {@code admin:all}, so a project member holding only the ID
 * has nothing to resolve it against.
 *
 * @param name Null when the registration is gone; the ID outlives it on jobs that ran there.
 */
public record WorkerInfo(UUID id, @Nullable String name) {
    public WorkerInfo {
        Objects.requireNonNull(id, "id");
    }

    public static WorkerInfo of(UUID id, @Nullable WorkerEntity worker) {
        return new WorkerInfo(id, worker == null ? null : worker.getName());
    }
}
