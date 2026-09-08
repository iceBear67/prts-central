package io.ib67.prts.admin;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "admin")
public interface AdminConfig {
    ListConfig list();

    interface ListConfig {
        @WithDefault("50")
        int maxPageSize();
    }
}
