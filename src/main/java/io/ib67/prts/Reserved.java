package io.ib67.prts;

import java.util.UUID;

/**
 * The id that stands in for "no row" where a column cannot be left empty: a key column, since
 * Postgres cannot key on null. It is one value so that two such columns cannot drift apart.
 *
 * <p>Do not reach for it outside a primary key. A column that may simply be absent says so with
 * null, and one that must name something real should reject the caller that has nothing to name —
 * substituting this there only renames the third state, and puts the same value in two different
 * key columns, where a swapped argument stops looking wrong.
 */
public final class Reserved {

    public static final UUID ID = new UUID(0, 0);

    private Reserved() {
    }
}
