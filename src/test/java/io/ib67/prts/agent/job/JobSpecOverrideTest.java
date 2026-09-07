package io.ib67.prts.agent.job;

import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobSpecOverrideTest {

    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final JobSpec BASE = new JobSpec(
            "base:1",
            Map.of("A", "1", "B", "2"),
            Map.of("team", "core"),
            List.of("echo", "base"),
            Map.of(),
            60L,
            "base-lock",
            Map.of("TOKEN", "s3cr3t"));

    /** Returns whatever it is handed, so a test observes merging rather than the authorizer. */
    private final JobSpecOverrideAuthorizer authorizer =
            mock(JobSpecOverrideAuthorizer.class, invocation -> invocation.getArgument(0));

    private static JobSpecOverride override(
            String image, Map<String, String> environment, Map<String, String> labels,
            List<String> command, Map<UUID, JobSpec.VolumeSpec> volumes, Long timeout, String lock) {
        return new JobSpecOverride(image, environment, labels, command, volumes, timeout, lock);
    }

    private static JobSpecOverride nothing() {
        return override(null, null, null, null, null, null, null);
    }

    @Test
    void absentFieldsLeaveTheTemplateUntouched() {
        var result = nothing().applyTo(BASE, authorizer);

        assertEquals(BASE.image(), result.image());
        assertEquals(BASE.environment(), result.environment());
        assertEquals(BASE.labels(), result.labels());
        assertEquals(BASE.command(), result.command());
        assertEquals(BASE.volumes(), result.volumes());
        assertEquals(BASE.timeout(), result.timeout());
        assertEquals(BASE.lock(), result.lock());
        verifyNoInteractions(authorizer);
    }

    @Test
    void scalarsReplace() {
        var result = override("over:2", null, null, null, null, 90L, "other-lock")
                .applyTo(BASE, authorizer);

        assertEquals("over:2", result.image());
        assertEquals(90L, result.timeout());
        assertEquals("other-lock", result.lock());
    }

    @Test
    void mapsMergeByKey() {
        var result = override(null, Map.of("B", "override", "C", "3"), null, null, null, null, null)
                .applyTo(BASE, authorizer);

        assertEquals(Map.of("A", "1", "B", "override", "C", "3"), result.environment());
        assertEquals(BASE.labels(), result.labels());
    }

    @Test
    void listsAppend() {
        var result = override(null, null, null, List.of("extra"), null, null, null)
                .applyTo(BASE, authorizer);

        assertEquals(List.of("echo", "base", "extra"), result.command());
    }

    @Test
    void volumesMergeByKey() {
        var volume = new JobSpec.VolumeSpec("/data", 1024L);
        var result = override(null, null, null, null, Map.of(VOLUME, volume), null, null)
                .applyTo(BASE, authorizer);

        assertEquals(Map.of(VOLUME, volume), result.volumes());
    }

    @Test
    void theAuthorizerSeesOnlyUserSuppliedInput() {
        override(null, Map.of("C", "3"), null, List.of("extra"), null, null, null)
                .applyTo(BASE, authorizer);

        // Never the merged result: gating a merged map would make the caller answer for template
        // values they never supplied, and gating it afterwards would let a denied key slip in.
        verify(authorizer).environment(Map.of("C", "3"));
        verify(authorizer).command(List.of("extra"));
        verify(authorizer, never()).image(any());
        verify(authorizer, never()).labels(any());
        verify(authorizer, never()).volumes(any());
        verify(authorizer, never()).timeout(anyLong());
        verify(authorizer, never()).lock(any());
    }

    @Test
    void anEmptyOverrideIsStillSuppliedAndStillGated() {
        override(null, Map.of(), null, List.of(), null, null, "").applyTo(BASE, authorizer);

        verify(authorizer).environment(Map.of());
        verify(authorizer).command(List.of());
        verify(authorizer).lock("");
    }

    @Test
    void aRejectedFieldAbortsTheWholeSpec() {
        var denying = mock(JobSpecOverrideAuthorizer.class);
        when(denying.image(any())).thenThrow(new ForbiddenException("missing permission"));

        var override = override("over:2", null, null, null, null, null, null);
        assertThrows(ForbiddenException.class, () -> override.applyTo(BASE, denying));
    }

    @Test
    void secretsCarryOverFromTheTemplate() {
        var result = override("over:2", null, null, null, null, null, null).applyTo(BASE, authorizer);

        assertEquals(BASE.secret(), result.secret());
    }

    @Test
    void aBlankLockOverrideMeansNoLock() {
        var result = override(null, null, null, null, null, null, "   ").applyTo(BASE, authorizer);

        assertEquals("", result.lock());
    }

    @Test
    void rejectsNullArguments() {
        var override = nothing();
        assertThrows(NullPointerException.class, () -> override.applyTo(null, authorizer));
        assertThrows(NullPointerException.class, () -> override.applyTo(BASE, null));
    }
}
