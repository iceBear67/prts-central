package io.ib67.prts.job;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "job")
public interface JobConfig {
    Log log();

    JobList list();

    Pending pending();

    interface Log {
        @WithDefault("20")
        int maxPageSize();
    }

    interface JobList {
        @WithDefault("50")
        int maxPageSize();
    }

    /** Configuration for the pending job queue. */
    interface Pending {
        @WithDefault("PT1S")
        Duration interval();

        /** Maximum time a queued request can wait before expiring. */
        @WithDefault("PT1H")
        Duration ttl();

        @WithDefault("100")
        int maxPerProject();

        /** Initial retry delay after an unsuccessful dispatch attempt, doubling up to maxBackoff. */
        @WithDefault("PT2S")
        Duration backoff();

        @WithDefault("PT30S")
        Duration maxBackoff();

        /** Maximum number of pending jobs to claim and attempt per dispatch tick. */
        @WithDefault("20")
        int batch();
    }
}
