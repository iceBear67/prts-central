package io.ib67.prts.agent.acp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "acp")
public interface AcpConfig {

    /** Maximum concurrent ACP sessions per job. */
    @WithDefault("32")
    int maxSessionsPerJob();

    /** Maximum concurrent viewers per job agent. */
    @WithDefault("8")
    int maxViewersPerJob();

    /** Maximum page size for session and event listings. */
    @WithDefault("50")
    int maxPageSize();
}
