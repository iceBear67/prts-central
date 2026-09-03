package io.ib67.prts.secret;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;

@ConfigMapping(prefix = "secret")
public interface SecretConfig {

    /**
     * AES keys by id, each base64 of 16, 24 or 32 bytes — generate one with
     * {@code openssl rand -base64 32}. The id it was sealed under is part of every stored value, so a
     * key must stay here for as long as one row still names it: drop it only once
     * {@code scripts/secrets.py rotate} reports nothing left. No default outside {@code %dev} on
     * purpose — a deployment that forgets these must fail to start rather than seal secrets with a
     * key from this repository.
     */
    Map<String, String> keys();

    /** Id in {@link #keys()} that new values are sealed under, and that a rotation re-seals to. */
    String activeKey();

    /** Bound on a single value, so a caller cannot fill the table through this endpoint. */
    @WithDefault("4096")
    int maxValueLength();

    @WithDefault("256")
    int maxDescriptionLength();
}
