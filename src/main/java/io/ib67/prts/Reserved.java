package io.ib67.prts;

import java.util.UUID;

/**
 * Sentinel UUID used in composite primary keys where null values are not allowed by the database schema.
 *
 * <p>Use only for non-nullable key columns representing a global or unset entity. Regular nullable columns should use null instead.
 */
public final class Reserved {

    public static final UUID ID = new UUID(0, 0);

    private Reserved() {
    }
}
