package io.ib67.prts.project;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "project")
public interface ProjectConfig {
    ListConfig list();

    /** Maximum page size for project resource listings (templates, secrets, sub-accounts, volumes). */
    interface ListConfig {
        @WithDefault("50")
        int maxPageSize();
    }
}
