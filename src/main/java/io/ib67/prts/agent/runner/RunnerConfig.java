package io.ib67.prts.agent.runner;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "runner")
public interface RunnerConfig {
    String secret();
}
