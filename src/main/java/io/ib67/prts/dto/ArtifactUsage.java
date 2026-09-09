package io.ib67.prts.dto;

/**
 * How many artifacts occupy how many bytes, over whatever scope the caller counted —
 * a job's in-flight upload reservations, or every artifact stored.
 */
public record ArtifactUsage(long count, long bytes) {
}
