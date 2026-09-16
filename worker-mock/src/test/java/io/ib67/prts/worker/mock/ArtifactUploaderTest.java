package io.ib67.prts.worker.mock;

import io.ib67.prts.worker.mock.protocol.Inbound;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storage half of an artifact upload: the bytes really do go to the URL the control plane
 * presigned, on the method it named, and a refusal is not swallowed.
 */
class ArtifactUploaderTest {

    private static final byte[] CONTENT = "hello".getBytes();

    private final ArtifactUploader uploader = new ArtifactUploader();

    @Test
    void theBytesArePutWithTheMethodTheControlPlaneNamed() {
        try (var storage = new LocalStorage()) {
            uploader.put(presigned(storage.url("/artifacts/1/report.txt"), "PUT"), CONTENT);

            var stored = storage.stored().get(0);
            assertEquals("PUT", stored.method());
            assertEquals("/artifacts/1/report.txt", stored.path());
            assertArrayEquals(CONTENT, stored.body());
        }
    }

    @Test
    void aPresignedPostIsPostedRatherThanPut() {
        try (var storage = new LocalStorage()) {
            uploader.put(presigned(storage.url("/obj"), "POST"), CONTENT);

            assertEquals("POST", storage.stored().get(0).method());
        }
    }

    @Test
    void storageRefusingTheBytesIsReportedWithItsStatus() {
        try (var storage = new LocalStorage()) {
            storage.refuse(403);

            var failure = assertThrows(IllegalStateException.class,
                    () -> uploader.put(presigned(storage.url("/obj"), "PUT"), CONTENT));

            assertTrue(failure.getMessage().contains("HTTP 403"), failure.getMessage());
        }
    }

    @Test
    void storageThatCannotBeReachedIsReported() {
        var storage = new LocalStorage();
        var url = storage.url("/obj");
        storage.close();

        var failure = assertThrows(IllegalStateException.class,
                () -> uploader.put(presigned(url, "PUT"), CONTENT));

        assertTrue(failure.getMessage().contains("cannot upload"), failure.getMessage());
    }

    private static Inbound.PresignedUpload presigned(String url, String method) {
        return new Inbound.PresignedUpload(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "report.txt",
                "artifacts/report.txt",
                url,
                method,
                Instant.parse("2026-01-01T00:00:00Z"),
                CONTENT.length);
    }
}
