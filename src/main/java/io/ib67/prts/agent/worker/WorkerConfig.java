package io.ib67.prts.agent.worker;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.NotBlank;

@ConfigMapping(prefix = "worker")
public interface WorkerConfig {
    /** Blank would authenticate a blank {@code X-Worker-Token}, which is also why there is no default. */
    @NotBlank(message = "worker.secret must not be blank: an empty secret authenticates an empty header")
    String secret();
}
