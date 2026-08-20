package io.ib67.prts.storage;

import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "storage")
public interface StorageConfig {
    String bucket();

    Duration presignDuration();

    @WithDefault("2G")
    MemorySize maxFileSize();

    @WithDefault("5G")
    MemorySize maxJobSize();

    @WithDefault("256")
    int maxPendingUploads();
}
