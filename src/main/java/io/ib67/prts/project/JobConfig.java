package io.ib67.prts.project;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "job")
public interface JobConfig {
    Log log();

    interface Log {
        @WithDefault("20")
        int maxPageSize();
    }
}
