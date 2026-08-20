package io.ib67.prts.dto;

import io.ib67.prts.storage.StorageService;

import java.time.Instant;

public record PresignedUrlView(
        String url,
        String method,
        Instant expiresAt
) {
    public static PresignedUrlView of(StorageService.PresignedGet presigned) {
        return new PresignedUrlView(presigned.url(), presigned.method(), presigned.expiresAt());
    }
}
