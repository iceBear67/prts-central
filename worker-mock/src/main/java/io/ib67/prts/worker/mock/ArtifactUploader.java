package io.ib67.prts.worker.mock;

import io.ib67.prts.worker.mock.protocol.Inbound;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The storage half of an artifact upload: a plain PUT of the bytes to the URL the control plane
 * presigned, on the method it named.
 */
final class ArtifactUploader {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * @throws IllegalStateException if storage refuses the upload
     */
    void put(Inbound.PresignedUpload upload, byte[] content) {
        var request = HttpRequest.newBuilder(URI.create(upload.url()))
                .timeout(TIMEOUT)
                // The body publisher states the length, and it is the one the control plane reserved.
                .method(upload.method(), HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("cannot upload " + upload.name() + " to storage: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while uploading " + upload.name(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "storage refused the upload of " + upload.name() + ": HTTP " + response.statusCode());
        }
    }
}
