package io.ib67.prts.secret;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;

@ConfigMapping(prefix = "secret")
public interface SecretConfig {

    /**
     * Map of key ID to base64-encoded AES key (16, 24, or 32 bytes).
     * Existing keys must be retained as long as rows are encrypted with them.
     */
    Map<String, String> keys();

    /** Key ID in {@link #keys()} used to encrypt new secrets. */
    String activeKey();

    /** Maximum allowed plaintext length for a secret value. */
    @WithDefault("4096")
    int maxValueLength();

    @WithDefault("256")
    int maxDescriptionLength();
}
