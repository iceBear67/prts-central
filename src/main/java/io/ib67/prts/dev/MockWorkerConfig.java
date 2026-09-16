package io.ib67.prts.dev;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.net.URI;
import java.util.UUID;

/** The mock worker dev mode runs for itself. Has no effect outside the dev profile. */
@ConfigMapping(prefix = "dev.mock-worker")
public interface MockWorkerConfig {
    @WithDefault("true")
    boolean enabled();

    /** Fixed, so restarting dev mode re-registers the same row instead of leaving a dead one behind. */
    @WithDefault("00000000-0000-7000-8000-000000000001")
    UUID id();

    @WithDefault("mock-worker")
    String name();

    /** Where the worker connects back to: dev mode's own HTTP port. */
    @WithDefault("http://localhost:${quarkus.http.port:8080}")
    URI url();

    Resources resources();

    /** What a job that carries no {@code prts.mock} label gets: demo, succeed, fail, hang or agent. */
    @WithDefault("demo")
    String script();

    /** What the worker reports it has, which is what placement filters on. */
    interface Resources {
        // Above every seeded resource class, so placement never leaves a job unplaced for capacity.
        @WithDefault("32")
        int cpus();

        @WithDefault("131072")
        int memory();

        @WithDefault("1048576")
        int disk();
    }
}
