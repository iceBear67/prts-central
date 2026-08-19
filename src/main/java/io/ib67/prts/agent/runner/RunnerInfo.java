package io.ib67.prts.agent.runner;

import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot a runner reports about itself. {@link #current} and {@link #capacity} are siblings;
 * {@link #pending} is the runner's own count so a reconnect can replace our view wholesale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunnerInfo {
    /** Remaining resources. {@code null} together with a null {@link #capacity} means unbounded. */
    @Nullable
    protected Resources current;
    /** Physical maximum. {@code null} means this runner accepts any {@link ResourceClass}. */
    @Nullable
    protected Resources capacity;
    protected int pending;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Resources {
        protected int numCpus;
        protected int numMemories;
        protected int numDisks;
    }
}
