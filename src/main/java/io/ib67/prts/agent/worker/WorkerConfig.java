package io.ib67.prts.agent.worker;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.NotBlank;

@ConfigMapping(prefix = "worker")
public interface WorkerConfig {
    /** Shared secret required for {@code X-Worker-Token} worker authentication. */
    @NotBlank(message = "worker.secret must not be blank: an empty secret authenticates an empty header")
    String secret();
}
