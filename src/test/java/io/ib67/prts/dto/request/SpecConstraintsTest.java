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
 * Pins the declared caps on the three shapes that build a {@link JobSpec}.
 *
 * <p>These rules are otherwise only exercised through HTTP, which is tier C. Running a real
 * {@link Validator} here keeps them verifiable locally — in particular that {@code @Valid} cascades
 * into map values, which is the only thing enforcing a mount point once a spec reaches a worker.
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

    /** A mount point only reaches a worker through one of these maps, so the cascade is the whole rule. */
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

    /** The attach payload strips first, so surrounding space is a typo rather than a refusal. */
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

    /** {@code {max}} has to render the constant, or the message and the cap can drift apart. */
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

    /** A name the catalogue could never hold is a bad request, not a lookup that will miss. */
    @Test
    void aResourceClassNameIsCheckedAgainstTheCatalogueRule() {
        assertEquals(
                Set.of("scope.resourceClass is not a valid resource class name"),
                messagesOf(new TaskScope(Map.of(), Map.of(), "not a class name")));
        assertTrue(messagesOf(new TaskScope(Map.of(), Map.of(), "small")).isEmpty());
    }
}
