package io.ib67.prts.agent.worker;

import jakarta.annotation.Nullable;
import lombok.*;

@Getter
public class RegisteredWorker {
    protected final String name;
    protected final WorkerClient rpc;
    /**
     * {@code null} means this worker has unbounded resources and no pending work we know of.
     */
    @Setter
    @Nullable
    protected Info info;

    public RegisteredWorker(String name, WorkerClient rpc, @Nullable Info info) {
        this.name = name;
        this.rpc = rpc;
        this.info = info;
    }

    public int pendingJobCount() {
        return info == null ? 0 : info.getPending();
    }

    /**
     * Snapshot a worker reports about itself. {@link #current} and {@link #capacity} are siblings;
     * {@link #pending} is the worker's own count so a reconnect can replace our view wholesale.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Info {
        /** Remaining resources. {@code null} together with a null {@link #capacity} means unbounded. */
        @Nullable
        protected Resources current;
        /** Physical maximum. {@code null} means this worker accepts any {@link ResourceClass}. */
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
}
