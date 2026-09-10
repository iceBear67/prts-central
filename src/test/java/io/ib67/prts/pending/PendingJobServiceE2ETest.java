package io.ib67.prts.pending;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.job.JobConfig;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.inTx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link PendingJobService} operations: claiming entries, finalizing attempts, and exponential backoff.
 */
@QuarkusTest
@Tag("e2e")
class PendingJobServiceE2ETest {

    private static final int NO_LIMIT = 100;
    private static final int ROUNDS_TO_THE_CEILING = 8;

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;
    @Inject
    PendingJobService pendingJobService;
    @Inject
    PendingJobDispatcher dispatcher;
    @Inject
    WorkerService workerService;
    @Inject
    JobConfig jobConfig;

    private UUID project;
    private Fixtures.Actor alice;
    private UUID template;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.createProject("mine");
        alice = fixtures.createActor("alice");
        template = fixtures.createTemplate("build", null, fixtures.createResourceClass("small"));
    }

    @Test
    void claimingTakesTheDueOnesAndMarksThemInFlight() {
        var first = fixtures.createQueuedJob(project, alice, template, "small");
        var second = fixtures.createQueuedJob(project, alice, template, "small");

        var claimed = pendingJobService.claimDue(NO_LIMIT);

        assertEquals(List.of(first, second), claimed.stream().map(PendingJobService.Attempt::id).toList());
        assertEquals(PendingJobState.DISPATCHING, stateOf(first));
        assertEquals(PendingJobState.DISPATCHING, stateOf(second));
    }

    /** Verifies that all required fields are copied into the Attempt record. */
    @Test
    void anAttemptCarriesWhatTheLaunchNeeds() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");

        var attempt = pendingJobService.claimDue(NO_LIMIT).getFirst();

        assertEquals(entry, attempt.id());
        assertEquals(project, attempt.projectId());
        assertEquals(alice.id(), attempt.requestedBy());
        assertEquals(template, attempt.request().templateId());
        assertEquals("small", attempt.request().resourceClass());
        assertNull(attempt.request().override());
    }

    /** In-flight DISPATCHING entries cannot be claimed by another pass. */
    @Test
    void claimingTwiceDoesNotHandTheSameOneOut() {
        fixtures.createQueuedJob(project, alice, template, "small");
        pendingJobService.claimDue(NO_LIMIT);

        assertEquals(List.of(), pendingJobService.claimDue(NO_LIMIT));
    }

    @Test
    void aTickTakesNoMoreThanItsBatch() {
        var first = fixtures.createQueuedJob(project, alice, template, "small");
        var second = fixtures.createQueuedJob(project, alice, template, "small");

        var claimed = pendingJobService.claimDue(1);

        assertEquals(List.of(first), claimed.stream().map(PendingJobService.Attempt::id).toList());
        assertEquals(PendingJobState.QUEUED, stateOf(second));
    }

    @Test
    void oneWhoseTurnHasNotComeIsLeftAlone() {
        var now = Instant.now();
        var later = fixtures.createQueuedJob(project, alice, template, "small",
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(5)));

        assertEquals(List.of(), pendingJobService.claimDue(NO_LIMIT));
        assertEquals(PendingJobState.QUEUED, stateOf(later));
    }

    // ---- settling the attempt ----

    @Test
    void aDispatchedAttemptRecordsTheJobItBecame() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        var job = UUID.randomUUID();
        inFlight(entry);

        pendingJobService.markDispatched(entry, job);

        var settled = read(entry);
        assertEquals(PendingJobState.DISPATCHED, settled.getState());
        assertEquals(job, settled.getJobId());
        assertEquals(1, settled.getAttempts());
        assertNull(settled.getLastError());
    }

    @Test
    void aFailedAttemptKeepsTheReasonItGaveUpFor() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        inFlight(entry);

        pendingJobService.markFailed(entry, "no such template");

        var settled = read(entry);
        assertEquals(PendingJobState.FAILED, settled.getState());
        assertEquals("no such template", settled.getLastError());
        assertEquals(1, settled.getAttempts());
    }

    /**
     * Ensures attempts cancelled mid-execution remain CANCELLED when settled.
     */
    @Test
    void anEntryCancelledMidAttemptStaysCancelled() {
        var dispatched = fixtures.createQueuedJob(project, alice, template, "small");
        var requeued = fixtures.createQueuedJob(project, alice, template, "small");
        var failed = fixtures.createQueuedJob(project, alice, template, "small");
        pendingJobService.claimDue(NO_LIMIT);
        inTx(() -> PendingJob.cancelActive(project));

        pendingJobService.markDispatched(dispatched, UUID.randomUUID());
        pendingJobService.requeue(requeued, "no worker could take the job yet");
        pendingJobService.markFailed(failed, "gave up");

        assertEquals(PendingJobState.CANCELLED, stateOf(dispatched));
        assertEquals(PendingJobState.CANCELLED, stateOf(requeued));
        assertEquals(PendingJobState.CANCELLED, stateOf(failed));
        assertEquals(0, read(dispatched).getAttempts());
    }

    @Test
    void requeueingPutsItBackInLineBehindTheBackoff() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        inFlight(entry);

        var waited = requeue(entry, "no worker could take the job yet");

        var settled = read(entry);
        assertEquals(PendingJobState.QUEUED, settled.getState());
        assertEquals(1, settled.getAttempts());
        assertEquals("no worker could take the job yet", settled.getLastError());
        waited.assertIs(jobConfig.pending().backoff());
    }

    /** Requeued entries are not immediately due. */
    @Test
    void aRequeuedEntryIsNotClaimedStraightBack() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        inFlight(entry);
        pendingJobService.requeue(entry, "no worker could take the job yet");

        assertEquals(List.of(), pendingJobService.claimDue(NO_LIMIT));
    }

    @Test
    void eachAttemptWaitsLongerThanTheLast() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");

        inFlight(entry);
        var first = requeue(entry, "again").atMost();
        inFlight(entry);
        var second = requeue(entry, "again").atMost();

        assertTrue(second.compareTo(first) > 0, second + " should be longer than " + first);
    }

    @Test
    void theBackoffStopsAtItsCeiling() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");

        Wait waited = null;
        for (var round = 0; round < ROUNDS_TO_THE_CEILING; round++) {
            inFlight(entry);
            waited = requeue(entry, "again");
        }

        waited.assertIs(jobConfig.pending().maxBackoff());
    }

    @Test
    void aQueuedEntryCanBeCancelled() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");

        pendingJobService.cancel(project, entry);

        assertEquals(PendingJobState.CANCELLED, stateOf(entry));
    }

    /** In-flight entries cannot be cancelled (returns 409 Conflict). */
    @Test
    void oneAlreadyInFlightCannotBeCancelled() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        inFlight(entry);

        var thrown = assertThrows(ClientErrorException.class,
                () -> pendingJobService.cancel(project, entry));

        assertEquals(409, thrown.getResponse().getStatus());
        assertEquals(PendingJobState.DISPATCHING, stateOf(entry));
    }

    @Test
    void anotherProjectsEntryCannotBeCancelledFromHere() {
        var other = fixtures.createProject("theirs");
        var entry = fixtures.createQueuedJob(other, alice, template, "small");

        assertThrows(NotFoundException.class, () -> pendingJobService.cancel(project, entry));
        assertEquals(PendingJobState.QUEUED, stateOf(entry));
    }

    @Test
    void anEntryIsOnlyFoundThroughItsOwnProject() {
        var other = fixtures.createProject("theirs");
        var entry = fixtures.createQueuedJob(other, alice, template, "small");

        assertTrue(inTx(() -> pendingJobService.findInProject(other, entry)).isPresent());
        assertTrue(inTx(() -> pendingJobService.findInProject(project, entry)).isEmpty());
    }

    /** Overdue entries expire even when no workers are connected. */
    @Test
    void theQueueStillAgesOutWhileNoWorkerIsConnected() {
        var now = Instant.now();
        var overdue = fixtures.createQueuedJob(project, alice, template, "small",
                now.minus(Duration.ofMinutes(1)), now);

        dispatcher.tick();

        assertEquals(PendingJobState.EXPIRED, stateOf(overdue));
    }

    @Test
    void nothingIsClaimedWhileNoWorkerIsConnected() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        assertFalse(workerService.hasSchedulableWorker());

        dispatcher.tick();

        assertEquals(PendingJobState.QUEUED, stateOf(entry));
    }

    private void inFlight(UUID id) {
        inTx(() -> PendingJob.<PendingJob>findById(id).setState(PendingJobState.DISPATCHING));
    }

    private Wait requeue(UUID id, String reason) {
        var before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        pendingJobService.requeue(id, reason);
        var after = Instant.now();

        var next = read(id).getNextAttemptAt();
        return new Wait(Duration.between(after, next), Duration.between(before, next));
    }

    private record Wait(Duration atLeast, Duration atMost) {
        void assertIs(Duration expected) {
            assertTrue(atLeast.compareTo(expected) <= 0 && atMost.compareTo(expected) >= 0,
                    "expected a wait of " + expected + ", measured " + atLeast + ".." + atMost);
        }
    }

    private PendingJob read(UUID id) {
        return inTx(() -> PendingJob.<PendingJob>findById(id));
    }

    private PendingJobState stateOf(UUID id) {
        return inTx(() -> PendingJob.<PendingJob>findById(id).getState());
    }
}
