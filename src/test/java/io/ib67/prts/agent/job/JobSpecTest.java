package io.ib67.prts.agent.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.job.entity.Project;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class JobSpecTest {

    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final ObjectMapper mapper = new ObjectMapper();

    private static JobSpec spec(String lock, Map<String, String> secret) {
        return new JobSpec("img:1", Map.of("A", "1"), Map.of("team", "core"),
                List.of("run"), Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)),
                60L, lock, secret);
    }

    @Test
    void nullContainersNormalizeToEmpty() {
        var spec = new JobSpec("img:1", null, null, null, null, 0L, null, null);

        assertEquals(Map.of(), spec.environment());
        assertEquals(Map.of(), spec.labels());
        assertEquals(List.of(), spec.command());
        assertEquals(Map.of(), spec.volumes());
        assertEquals(Map.of(), spec.secret());
        assertEquals("", spec.lock());
    }

    @Test
    void aBlankLockMeansNoLock() {
        assertEquals("", spec("   ", Map.of()).lock());
        assertEquals("", spec("", Map.of()).lock());
        assertEquals("build", spec("build", Map.of()).lock());
    }

    @Test
    void imageIsRequired() {
        assertThrows(NullPointerException.class,
                () -> new JobSpec(null, null, null, null, null, 0L, null, null));
    }

    @Test
    void aVolumeNeedsAMountPoint() {
        assertThrows(NullPointerException.class, () -> new JobSpec.VolumeSpec(null, 1024L));
    }

    @Test
    void withSecretReplacesOnlyTheSecrets() {
        var base = spec("build", Map.of("OLD", "value"));
        var attached = base.withSecret(Map.of("TOKEN", "s3cr3t"));

        assertEquals(Map.of("TOKEN", "s3cr3t"), attached.secret());
        assertEquals(base.image(), attached.image());
        assertEquals(base.environment(), attached.environment());
        assertEquals(base.volumes(), attached.volumes());
        assertEquals(base.lock(), attached.lock());
    }

    @Test
    void toStringRedactsSecretValues() {
        var rendered = spec("build", Map.of("TOKEN", "s3cr3t")).toString();

        assertFalse(rendered.contains("s3cr3t"), rendered);
        assertFalse(rendered.contains("TOKEN"), rendered);
        assertTrue(rendered.contains("secret=1 entries"), rendered);
        assertTrue(rendered.contains("image=img:1"), rendered);
    }

    /** Verifies Jackson serialization round-trip for JSONB persistence. */
    @Test
    void survivesAJacksonRoundTrip() throws Exception {
        var spec = spec("build", Map.of());

        var restored = mapper.readValue(mapper.writeValueAsString(spec), JobSpec.class);

        assertEquals(spec, restored);
    }

    @Test
    void secretsAreNeverSerialized() throws Exception {
        var json = mapper.writeValueAsString(spec("build", Map.of("TOKEN", "s3cr3t")));

        assertFalse(json.contains("s3cr3t"), json);
        assertFalse(json.contains("secret"), json);
        assertEquals(Map.of(), mapper.readValue(json, JobSpec.class).secret());
    }

    private static WorkerVolume volumeOwnedBy(UUID projectId) {
        var volume = new WorkerVolume();
        volume.setId(VOLUME);
        volume.setProject(Project.builder().id(projectId).name("p").build());
        return volume;
    }

    private static JobSpec specRequiring(Map<UUID, JobSpec.VolumeSpec> volumes) {
        return new JobSpec("img:1", null, null, null, volumes, 0L, null, null);
    }

    @Test
    void aSpecWithoutVolumesQueriesNothing() {
        try (var workerVolume = mockStatic(WorkerVolume.class)) {
            specRequiring(Map.of()).requireVolumesIn(PROJECT);

            workerVolume.verifyNoInteractions();
        }
    }

    @Test
    void aVolumeOfThisProjectIsAccepted() {
        try (var workerVolume = mockStatic(WorkerVolume.class)) {
            workerVolume.when(() -> WorkerVolume.listByIds(any()))
                    .thenReturn(List.of(volumeOwnedBy(PROJECT)));
            var spec = specRequiring(Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)));

            assertDoesNotThrow(() -> spec.requireVolumesIn(PROJECT));
        }
    }

    @Test
    void aVolumeOfAnotherProjectIsForbidden() {
        try (var workerVolume = mockStatic(WorkerVolume.class)) {
            workerVolume.when(() -> WorkerVolume.listByIds(any()))
                    .thenReturn(List.of(volumeOwnedBy(OTHER_PROJECT)));
            var spec = specRequiring(Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)));

            assertThrows(ForbiddenException.class, () -> spec.requireVolumesIn(PROJECT));
        }
    }

    @Test
    void aVolumeThatDoesNotExistIsRejected() {
        try (var workerVolume = mockStatic(WorkerVolume.class)) {
            workerVolume.when(() -> WorkerVolume.listByIds(any())).thenReturn(List.of());
            var spec = specRequiring(Map.of(VOLUME, new JobSpec.VolumeSpec("/data", 1024L)));

            assertThrows(BadRequestException.class, () -> spec.requireVolumesIn(PROJECT));
        }
    }
}
