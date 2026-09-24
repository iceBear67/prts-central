package io.ib67.prts.agent.worker;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.NotBlank;
 import jakarta.validation.constraints.Positive;

import java.time.Duration;

@ConfigMapping(prefix = "worker")
public interface WorkerConfig {
    /** Shared secret required for {@code X-Worker-Token} worker authentication. */
    @NotBlank(message = "worker.secret must not be blank: an empty secret authenticates an empty header")
    String secret();

    Timeout timeout();

    /**
     * How many job placements are kept in memory to check a worker's reports against. Only a
     * placement not yet claimed needs its entry, every other one is also in {@code Job.worker}: this
     * has to hold what can be placed within one {@code createJob} timeout.
     */
    @WithDefault("10000")
    @Positive(message = "worker.placement-cache-size must be positive: "
            + "a job not yet claimed by its worker is known only to this cache")
    int placementCacheSize();

    /**
     * How long the control plane waits for a worker to acknowledge one message.
     *
     * <p>Each bound sits between two committed transactions: without it a silent worker blocks its
     * caller forever.
     */
    interface Timeout {
        @WithDefault("PT30S")
        Duration createJob();

        @WithDefault("PT5S")
        Duration cancelJob();

        @WithDefault("PT10S")
        Duration interruptJob();

        /** Allocating a disk can outlast starting a container. */
        @WithDefault("PT1M")
        Duration volume();

        @WithDefault("PT3S")
        Duration agentFrame();

        /** How long closing a session waits for the close handshake. */
        @WithDefault("PT5S")
        Duration close();
    }
}
