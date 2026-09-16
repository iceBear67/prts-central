package io.ib67.prts.dto;

import java.util.EnumMap;
import java.util.Map;

/** Shared normalization for the {@code byState} groupings the statistics views publish. */
public final class Counts {

    private Counts() {
    }

    /**
     * Returns the grouping with every constant of {@code type} present, missing ones as zero, so a
     * client reading it never has to tell a key the server omitted from a count of none.
     */
    public static <E extends Enum<E>> Map<E, Long> filled(Class<E> type, Map<E, Long> counts) {
        var all = new EnumMap<E, Long>(type);
        for (var value : type.getEnumConstants()) {
            all.put(value, counts.getOrDefault(value, 0L));
        }
        return all;
    }
}
