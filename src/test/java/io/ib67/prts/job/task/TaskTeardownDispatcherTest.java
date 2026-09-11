package io.ib67.prts.job.task;

import io.ib67.prts.job.JobConfig;
import io.ib67.prts.job.task.entity.Task;
import io.ib67.prts.testing.InlineTransactions;
import io.ib67.prts.testing.MutedLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskTeardownDispatcherTest {

    private static final int BATCH = 7;
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    private final TaskService taskService = mock(TaskService.class);
    private final JobConfig jobConfig = mock(JobConfig.class, RETURNS_DEEP_STUBS);

    private final TaskTeardownDispatcher dispatcher = new TaskTeardownDispatcher();

    @BeforeEach
    void setUp() {
        dispatcher.taskService = taskService;
        dispatcher.jobConfig = jobConfig;
        when(jobConfig.task().teardownBatch()).thenReturn(BATCH);
    }

    /** Entities must be built before the static mock, which also stubs Lombok's builder(). */
    private static List<Task> tasks(UUID... ids) {
        return List.of(ids).stream().map(id -> Task.builder().id(id).name("t").build()).toList();
    }

    @Test
    void everyClosingTaskGetsAnotherPass() {
        var closing = tasks(FIRST, SECOND);

        try (var ignored = new InlineTransactions(); var entities = mockStatic(Task.class)) {
            entities.when(() -> Task.listClosing(BATCH)).thenReturn(closing);

            dispatcher.tick();
        }

        verify(taskService).teardown(FIRST);
        verify(taskService).teardown(SECOND);
    }

    @Test
    void nothingClosingMeansNothingToDo() {
        try (var ignored = new InlineTransactions(); var entities = mockStatic(Task.class)) {
            entities.when(() -> Task.listClosing(BATCH)).thenReturn(List.of());

            dispatcher.tick();
        }

        verifyNoInteractions(taskService);
    }

    /** One stuck task must not strand the others until the next sweep. */
    @Test
    void oneFailureDoesNotAbandonTheRest() {
        var closing = tasks(FIRST, SECOND);
        when(taskService.teardown(FIRST)).thenThrow(new IllegalStateException("worker is gone"));

        try (var ignored = new InlineTransactions(); var entities = mockStatic(Task.class);
             var ignoredLogs = new MutedLogs(TaskTeardownDispatcher.class)) {
            entities.when(() -> Task.listClosing(BATCH)).thenReturn(closing);

            assertDoesNotThrow(dispatcher::tick);
        }

        verify(taskService).teardown(SECOND);
    }
}
