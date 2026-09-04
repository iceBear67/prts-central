package io.ib67.prts.storage;

import io.quarkiverse.amazon.s3.runtime.S3Config;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

@ApplicationScoped
public class StorageService {
    @Inject
    S3Client s3;
    @Inject
    S3Config s3Config;
    @Inject
    StorageConfig storageConfig;

    private S3Presigner presigner;

    @PostConstruct
    void initPresigner() {
        var clientConfig = s3.serviceClientConfiguration();
        var builder = S3Presigner.builder()
                .s3Client(s3)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3Config.pathStyleAccess())
                        .accelerateModeEnabled(s3Config.accelerateMode())
                        .chunkedEncodingEnabled(false)
                        .useArnRegionEnabled(s3Config.useArnRegionEnabled())
                        .build());
        if (clientConfig.region() != null) {
            builder.region(clientConfig.region());
        }
        if (clientConfig.credentialsProvider() != null) {
            builder.credentialsProvider(clientConfig.credentialsProvider());
        }
        clientConfig.endpointOverride().ifPresent(builder::endpointOverride);
        presigner = builder.build();
    }

    @PreDestroy
    void closePresigner() {
        if (presigner != null) {
            presigner.close();
        }
    }

    public PresignedGet presignGet(String objectKey) {
        var duration = storageConfig.presignDuration();
        var expiresAt = Instant.now().plus(duration);
        var presigned = presigner.presignGetObject(r -> r
                .signatureDuration(duration)
                .getObjectRequest(b -> b
                        .bucket(storageConfig.bucket())
                        .key(objectKey)));
        return new PresignedGet(
                objectKey,
                presigned.url().toExternalForm(),
                presigned.httpRequest().method().name(),
                expiresAt);
    }

    public PresignedPut presignPut(String objectKey, long contentLength) {
        var duration = storageConfig.presignDuration();
        var expiresAt = Instant.now().plus(duration);
        var presigned = presigner.presignPutObject(r -> r
                .signatureDuration(duration)
                .putObjectRequest(b -> b
                        .bucket(storageConfig.bucket())
                        .key(objectKey)
                        .contentLength(contentLength)));
        return new PresignedPut(
                objectKey,
                presigned.url().toExternalForm(),
                presigned.httpRequest().method().name(),
                expiresAt);
    }

    public OptionalLong findObjectSize(String objectKey) {
        try {
            var head = s3.headObject(b -> b.bucket(storageConfig.bucket()).key(objectKey));
            var size = head.contentLength();
            return size == null ? OptionalLong.empty() : OptionalLong.of(size);
        } catch (NoSuchKeyException e) {
            return OptionalLong.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return OptionalLong.empty();
            }
            throw e;
        }
    }

    public void deleteQuietly(String objectKey) {
        try {
            s3.deleteObject(b -> b.bucket(storageConfig.bucket()).key(objectKey));
        } catch (RuntimeException ignored) {
            // best-effort cleanup of an object that never became an artifact
        }
    }

    public record PresignedPut(String objectKey, String url, String method, Instant expiresAt) {
        public PresignedPut {
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    public record PresignedGet(String objectKey, String url, String method, Instant expiresAt) {
        public PresignedGet {
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
