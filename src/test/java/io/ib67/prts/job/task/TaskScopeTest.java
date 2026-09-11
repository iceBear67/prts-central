package io.ib67.prts.job.task;

import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.agent.job.JobSpecOverride;
import io.ib67.prts.agent.job.JobSpecOverrideAuthorizer;
import io.ib67.prts.job.JobLauncher;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TaskScopeTest {

    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VOLUME = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static JobSpec spec(Map<String, String> environment, Map<String, String> labels) {
        return new JobSpec(
                "img:1", "builds it", environment, labels, List.of("run"), Map.of(), 60L, "", Map.of());
    }

    @Test
    void nullContainersNormalizeToEmpty() {
        var scope = new TaskScope(null, null, null);

        assertEquals(Map.of(), scope.environment());
        assertEquals(Map.of(), scope.labels());
        assertNull(scope.resourceClass());
    }

    @Test
    void aBlankResourceClassMeansTheTemplateDecides() {
        assertNull(new TaskScope(Map.of(), Map.of(), "   ").resourceClass());
        assertEquals("small", new TaskScope(Map.of(), Map.of(), "small").resourceClass());
    }

    @Test
    void defaultsSitAboveTheTemplate() {
        var scope = new TaskScope(Map.of("A", "task", "B", "task"), Map.of("tier", "task"), null);

        var merged = scope.defaultsTo(spec(Map.of("A", "template"), Map.of("tier", "template")));

        assertEquals(Map.of("A", "task", "B", "task"), merged.environment());
        assertEquals(Map.of("tier", "task"), merged.labels());
    }

    /** A job may specialize what its task declares, so the override still lands on top. */
    @Test
    void aCallerOverrideStillBeatsTheDefaults() {
        var scope = new TaskScope(Map.of("A", "task", "B", "task"), Map.of(), null);
        var base = scope.defaultsTo(spec(Map.of("A", "template"), Map.of()));
        var override =
                new JobSpecOverride(null, null, Map.of("A", "caller"), null, null, null, null, null);

        var merged = override.applyTo(base, JobLauncher.PRE_AUTHORIZED);

        assertEquals("caller", merged.environment().get("A"));
        assertEquals("task", merged.environment().get("B"));
    }

    @Test
    void anEmptyScopeLeavesTheSpecAlone() {
        var base = spec(Map.of("A", "1"), Map.of());

        assertSame(base, TaskScope.EMPTY.defaultsTo(base));
    }

    @Test
    void bindingMountsTheTasksVolumes() {
        var bound = TaskScope.bindTo(spec(Map.of(), Map.of()), TASK,
                Map.of(VOLUME, new JobSpec.VolumeSpec("/shared", 4096L)));

        assertEquals(Map.of(VOLUME, new JobSpec.VolumeSpec("/shared", 4096L)), bound.volumes());
    }

    /** Identity must win, or a caller could claim membership in a task by writing the variable itself. */
    @Test
    void identityIsNotOverridable() {
        var claimed = spec(
                Map.of(TaskScope.TASK_ID_ENV, "somebody-elses-task"),
                Map.of(TaskScope.TASK_ID_LABEL, "somebody-elses-task"));

        var bound = TaskScope.bindTo(claimed, TASK, Map.of());

        assertEquals(TASK.toString(), bound.environment().get(TaskScope.TASK_ID_ENV));
        assertEquals(TASK.toString(), bound.labels().get(TaskScope.TASK_ID_LABEL));
    }

    @Test
    void bindingKeepsEverythingElse() {
        var base = spec(Map.of("A", "1"), Map.of("tier", "core"));

        var bound = TaskScope.bindTo(base, TASK, Map.of());

        assertEquals(base.image(), bound.image());
        assertEquals(base.description(), bound.description());
        assertEquals(base.command(), bound.command());
        assertEquals(base.timeout(), bound.timeout());
        assertEquals(base.lock(), bound.lock());
        assertEquals("1", bound.environment().get("A"));
        assertEquals("core", bound.labels().get("tier"));
    }

    /**
     * A task's values reach every job under it without passing the override gate, so writing them has
     * to cost what overriding them would. Otherwise `task:manage` (a MEMBER) is a way around
     * `job:spec:environment`, `job:spec:labels` and `job:resource-class`.
     */
    @Test
    void writingAScopeIsChargedFieldByField() {
        var authorizer = mock(JobSpecOverrideAuthorizer.class);
        var scope = new TaskScope(Map.of("A", "1"), Map.of("tier", "core"), "large");

        scope.authorize(authorizer);

        verify(authorizer).environment(Map.of("A", "1"));
        verify(authorizer).labels(Map.of("tier", "core"));
        verify(authorizer).resourceClass("large");
        verifyNoMoreInteractions(authorizer);
    }

    /** Absent and empty are the same value here, so a scope that contributes nothing costs nothing. */
    @Test
    void anEmptyScopeIsChargedForNothing() {
        var authorizer = mock(JobSpecOverrideAuthorizer.class);

        TaskScope.EMPTY.authorize(authorizer);
        new TaskScope(null, null, "  ").authorize(authorizer);

        verifyNoInteractions(authorizer);
    }

    @Test
    void aRefusedFieldStopsTheWrite() {
        var authorizer = mock(JobSpecOverrideAuthorizer.class);
        when(authorizer.labels(any())).thenThrow(new ForbiddenException("missing permission"));

        assertThrows(ForbiddenException.class,
                () -> new TaskScope(Map.of("A", "1"), Map.of("tier", "core"), null).authorize(authorizer));
    }

    /**
     * JobSpec does not copy its collections, and a spec that skipped every override holds the managed
     * template entity's own maps — so neither layer may write into what it was handed.
     */
    @Test
    void neitherLayerMutatesTheSpecItWasGiven() {
        var environment = new LinkedHashMap<>(Map.of("A", "template"));
        var labels = new LinkedHashMap<>(Map.of("tier", "template"));
        var base = spec(environment, labels);

        var defaulted = new TaskScope(Map.of("A", "task"), Map.of("tier", "task"), null).defaultsTo(base);
        TaskScope.bindTo(defaulted, TASK, Map.of(VOLUME, new JobSpec.VolumeSpec("/shared", 1L)));

        assertEquals(Map.of("A", "template"), environment);
        assertEquals(Map.of("tier", "template"), labels);
        assertTrue(base.volumes().isEmpty());
    }
}
