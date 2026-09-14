package io.ib67.prts.dto;

/**
 * Aggregate artifact metrics representing total file count and byte size.
 */
public record ArtifactUsage(long count, long bytes) {
}
