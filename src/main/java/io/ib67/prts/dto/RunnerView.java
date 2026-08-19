package io.ib67.prts.dto;

import io.ib67.prts.agent.runner.Runner;
import io.ib67.prts.agent.runner.RunnerInfo;
import jakarta.annotation.Nullable;

import java.util.UUID;

/**
 * Public snapshot of a connected runner. Internal scheduling state and the RPC channel stay off this type.
 */
public record RunnerView(
        UUID id,
        String name,
        @Nullable RunnerInfo info
) {
    public static RunnerView of(UUID id, Runner runner) {
        return new RunnerView(id, runner.getName(), runner.getInfo());
    }
}
