package io.ib67.prts.project;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "job")
public interface JobConfig {
    Log log();

    Pending pending();

    interface Log {
        @WithDefault("20")
        int maxPageSize();
    }

    /** The queue that replays create requests no worker could take yet. */
    interface Pending {
        @WithDefault("PT1S")
        Duration interval();

        /**
         * How long a queued request keeps the authorization it was accepted with. It is refused once
         * that runs out rather than dispatched on a decision nobody would still make.
         */
        @WithDefault("PT1H")
        Duration ttl();

        @WithDefault("100")
        int maxPerProject();

        /** Delay after the first refused attempt; doubles per attempt up to {@link #maxBackoff()}. */
        @WithDefault("PT2S")
        Duration backoff();

        @WithDefault("PT30S")
        Duration maxBackoff();

        /** Attempts per tick. Each is a blocking hand-over, so the tick is as long as its batch. */
        @WithDefault("20")
        int batch();
    }
}
