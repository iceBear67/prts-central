package io.ib67.prts.dto;

import io.ib67.prts.storage.StorageService;

import java.time.Instant;
import java.util.Objects;

public record PresignedUrlView(
        String url,
        String method,
        Instant expiresAt
) {
    public PresignedUrlView {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public static PresignedUrlView of(StorageService.PresignedGet presigned) {
        return new PresignedUrlView(presigned.url(), presigned.method(), presigned.expiresAt());
    }
}
