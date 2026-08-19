package io.ib67.prts.agent.worker;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "worker")
public interface WorkerConfig {
    String secret();
}
