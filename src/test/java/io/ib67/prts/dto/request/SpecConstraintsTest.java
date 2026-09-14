package io.ib67.prts.dto.request;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.job.task.TaskScope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Bean Validation constraints on {@link JobSpecRequest}, {@link JobSpecOverride},
 * and {@link TaskScope}.
 */
class SpecConstraintsTest {

    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> messagesOf(Object bean) {
        return validator.validate(bean).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    private static JobSpecRequest specWith(Map<UUID, JobSpec.VolumeSpec> volumes) {
        return new JobSpecRequest("alpine", null, null, null, null, volumes, 0L, null);
    }

    private static Map<String, String> tooManyEntries() {
        return IntStream.rangeClosed(0, JobSpec.MAX_ENTRIES).boxed()
                .collect(Collectors.toMap(i -> "K" + i, i -> "v"));
    }

    /** Verifies that @Valid cascades into volume map values to validate volume specs. */
    @Test
    void validCascadesIntoAVolumeMapValue() {
        var messages = messagesOf(specWith(Map.of(VOLUME, new JobSpec.VolumeSpec("/../etc", 1L))));

        assertEquals(Set.of(JobSpec.VolumeSpec.MOUNT_POINT_REJECTED), messages);
    }

    @Test
    void anOverrideCascadesTheSameWay() {
        var override = new JobSpecOverride(
                null, null, null, null, null,
                Map.of(VOLUME, new JobSpec.VolumeSpec("/data/../..", 1L)), null, null);

        assertEquals(Set.of(JobSpec.VolumeSpec.MOUNT_POINT_REJECTED), messagesOf(override));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/../etc", "/data/../../etc", "/..", "/.", "/data/./cache", "data", "", "/",
            "/data/", "/data//cache", "/data cache", "/data "})
    void anUnusableMountPointIsRejected(String mountPoint) {
        assertEquals(
                Set.of(JobSpec.VolumeSpec.MOUNT_POINT_REJECTED),
                messagesOf(specWith(Map.of(VOLUME, new JobSpec.VolumeSpec(mountPoint, 1L)))));
    }

    /** Verifies that mount points in AttachVolumeRequest are trimmed prior to validation. */
    @Test
    void anAttachedMountPointIsStrippedBeforeItIsChecked() {
        assertTrue(messagesOf(new AttachVolumeRequest("  /data  ")).isEmpty());
        assertEquals(
                Set.of(JobSpec.VolumeSpec.MOUNT_POINT_REJECTED),
                messagesOf(new AttachVolumeRequest("/data/../etc")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/data", "/home/build/.gradle", "/a/b/c", "/..data", "/data.", "/x"})
    void anOrdinaryAbsolutePathIsAccepted(String mountPoint) {
        assertTrue(messagesOf(specWith(Map.of(VOLUME, new JobSpec.VolumeSpec(mountPoint, 1L)))).isEmpty());
    }

    @Test
    void anOverlongMountPointIsRejected() {
        var tooLong = "/" + "a".repeat(JobSpec.VolumeSpec.MAX_MOUNT_POINT_LENGTH);

        assertEquals(
                Set.of("mountPoint must be at most "
                        + JobSpec.VolumeSpec.MAX_MOUNT_POINT_LENGTH + " characters"),
                messagesOf(new AttachVolumeRequest(tooLong)));
    }

    /** Verifies that validation error messages interpolate constraint limit parameters correctly. */
    @Test
    void theMessageQuotesTheCapItEnforces() {
        var request = new JobSpecRequest(
                "x".repeat(JobSpec.MAX_IMAGE_LENGTH + 1), null, null, null, null, null, 0L, null);

        assertEquals(
                Set.of("spec.image must be at most " + JobSpec.MAX_IMAGE_LENGTH + " characters"),
                messagesOf(request));
    }

    @Test
    void aTimeoutBeyondTheCeilingIsRejected() {
        var request = new JobSpecRequest(
                "alpine", null, null, null, null, null, JobSpec.MAX_TIMEOUT_SECONDS + 1, null);

        assertEquals(
                Set.of("spec.timeout must be at most " + JobSpec.MAX_TIMEOUT_SECONDS + " seconds"),
                messagesOf(request));
    }

    @Test
    void tooManyEnvironmentEntriesAreRejected() {
        var request = new JobSpecRequest(
                "alpine", null, tooManyEntries(), null, null, null, 0L, null);

        assertEquals(
                Set.of("spec.environment must have at most " + JobSpec.MAX_ENTRIES + " entries"),
                messagesOf(request));
    }

    @Test
    void anOverlongCommandArgumentIsRejected() {
        var request = new JobSpecRequest(
                "alpine", null, null, null,
                List.of("x".repeat(JobSpec.MAX_ARGUMENT_LENGTH + 1)), null, 0L, null);

        assertEquals(
                Set.of("an argument must be at most " + JobSpec.MAX_ARGUMENT_LENGTH + " characters"),
                messagesOf(request));
    }

    @Test
    void aScopeIsBoundedToo() {
        var scope = new TaskScope(tooManyEntries(), Map.of(), null);

        assertEquals(
                Set.of("scope.environment must have at most " + JobSpec.MAX_ENTRIES + " entries"),
                messagesOf(scope));
    }

    /** Verifies that invalid resource class names fail pattern validation. */
    @Test
    void aResourceClassNameIsCheckedAgainstTheCatalogueRule() {
        assertEquals(
                Set.of("scope.resourceClass is not a valid resource class name"),
                messagesOf(new TaskScope(Map.of(), Map.of(), "not a class name")));
        assertTrue(messagesOf(new TaskScope(Map.of(), Map.of(), "small")).isEmpty());
    }
}
