package io.ib67.prts.dto;

/**
 * Aggregate storage metrics representing a total object count and byte size.
 */
public record StorageUsage(long count, long bytes) {
}
