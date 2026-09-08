package io.ib67.prts.pending;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.JobLauncher;
import io.ib67.prts.project.JobService;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PendingJobDispatcherTest {

    private static final int BATCH = 7;

    private final PendingJobService pendingJobService = mock(PendingJobService.class);
    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final JobService jobService = mock(JobService.class);
    private final WorkerService workerService = mock(WorkerService.class);
    private final JobConfig jobConfig = mock(JobConfig.class, RETURNS_DEEP_STUBS);

    private final PendingJobDispatcher dispatcher = new PendingJobDispatcher();

    @BeforeEach
    void setUp() {
        dispatcher.pendingJobService = pendingJobService;
        dispatcher.jobLauncher = jobLauncher;
        dispatcher.jobService = jobService;
        dispatcher.workerService = workerService;
        dispatcher.jobConfig = jobConfig;
        when(jobConfig.pending().batch()).thenReturn(BATCH);
        when(workerService.hasSchedulableWorker()).thenReturn(true);
    }

    private static PendingJobService.Attempt anAttempt() {
        return new PendingJobService.Attempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new JobRequest(UUID.randomUUID(), null, "small"));
    }

    private void due(PendingJobService.Attempt... attempts) {
        when(pendingJobService.claimDue(BATCH)).thenReturn(List.of(attempts));
    }

    private void launchYields(PendingJobService.Attempt attempt, UUID jobId, boolean scheduled) {
        when(jobLauncher.launch(eq(attempt.projectId()), eq(attempt.requestedBy()), eq(attempt.request()), any()))
                .thenReturn(new JobLauncher.CreatedJob(Job.builder().id(jobId).build(), scheduled));
    }

    /** Overdue pending jobs expire even when no workers are available. */
    @Test
    void overdueEntriesExpireEvenWithNoWorkerToDispatchTo() {
        when(workerService.hasSchedulableWorker()).thenReturn(false);

        dispatcher.tick();

        verify(pendingJobService).expireOverdue();
        verify(pendingJobService, never()).claimDue(anyInt());
        verifyNoInteractions(jobLauncher);
    }

    /** Queued requests are launched using PRE_AUTHORIZED since authorization was checked during enqueue. */
    @Test
    void aQueuedRequestIsLaunchedPreAuthorized() {
        var attempt = anAttempt();
        due(attempt);
        launchYields(attempt, UUID.randomUUID(), true);

        dispatcher.tick();

        verify(jobLauncher).launch(attempt.projectId(), attempt.requestedBy(), attempt.request(),
                JobLauncher.PRE_AUTHORIZED);
    }

    @Test
    void aPlacedJobIsMarkedDispatched() {
        var attempt = anAttempt();
        var jobId = UUID.randomUUID();
        due(attempt);
        launchYields(attempt, jobId, true);

        dispatcher.tick();

        verify(pendingJobService).markDispatched(attempt.id(), jobId);
        verify(pendingJobService, never()).requeue(any(), any());
        verifyNoInteractions(jobService);
    }

    /** Jobs that could not be placed are discarded and their pending entries are requeued. */
    @Test
    void anUnplacedJobIsDiscardedAndRequeued() {
        var attempt = anAttempt();
        var jobId = UUID.randomUUID();
        due(attempt);
        launchYields(attempt, jobId, false);

        dispatcher.tick();

        verify(jobService).discard(jobId);
        verify(pendingJobService).requeue(eq(attempt.id()), any());
        verify(pendingJobService, never()).markDispatched(any(), any());
    }

    @Test
    void aLaunchThatThrowsMarksThePendingJobFailed() {
        var attempt = anAttempt();
        due(attempt);
        when(jobLauncher.launch(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("template vanished"));

        dispatcher.tick();

        verify(pendingJobService).markFailed(eq(attempt.id()), any());
        verify(pendingJobService, never()).requeue(any(), any());
    }

    @Test
    void oneBadEntryDoesNotStrandTheRestOfTheBatch() {
        var doomed = anAttempt();
        var fine = anAttempt();
        due(doomed, fine);
        when(jobLauncher.launch(eq(doomed.projectId()), any(), any(), any()))
                .thenThrow(new IllegalStateException("template vanished"));
        launchYields(fine, UUID.randomUUID(), true);

        dispatcher.tick();

        verify(pendingJobService).markFailed(eq(doomed.id()), any());
        verify(pendingJobService).markDispatched(eq(fine.id()), any());
    }

    /** Exceptions thrown during a tick are caught to prevent cancelling scheduled execution. */
    @Test
    void aFailureInTheQueueItselfDoesNotKillTheTicker() {
        when(pendingJobService.expireOverdue()).thenThrow(new IllegalStateException("database is down"));

        assertDoesNotThrow(dispatcher::tick);

        verify(pendingJobService, never()).claimDue(anyInt());
    }

    @Test
    void aFailingClaimDoesNotKillTheTickerEither() {
        when(pendingJobService.claimDue(BATCH)).thenThrow(new IllegalStateException("database is down"));

        assertDoesNotThrow(dispatcher::tick);

        verifyNoInteractions(jobLauncher);
    }
}
