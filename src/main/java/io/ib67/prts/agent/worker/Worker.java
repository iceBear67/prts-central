package io.ib67.prts.agent.worker;

import jakarta.annotation.Nullable;
import lombok.*;

@Getter
public class Worker {
    protected final String name;
    protected final WorkerClient client;
    /** Null indicates unbounded resources and no pending jobs. */
    @Setter
    @Nullable
    protected Info info;
    /** Cached disabled state from the database. */
    @Setter
    protected volatile boolean disabled;

    public Worker(String name, WorkerClient client, @Nullable Info info) {
        this.name = name;
        this.client = client;
        this.info = info;
    }

    public int pendingJobCount() {
        return info == null ? 0 : info.getPending();
    }

    /** Resource snapshot reported by the worker. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Info {
        /** Remaining available resources. */
        @Nullable
        protected Resources current;
        /** Total resource capacity. Null indicates unlimited. */
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
