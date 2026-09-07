package io.ib67.prts.pending;

import io.ib67.prts.agent.worker.WorkerService;
import io.ib67.prts.project.JobConfig;
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
 * {@link PendingJobService}: claiming an entry, settling the attempt that followed, and the backoff
 * that decides when the next one may run.
 *
 * <p>A worker is connected only while {@code WorkerWebSocketE2ETest} runs, so a claimed entry is
 * settled here by hand rather than by {@link PendingJobDispatcher}, which would find nothing to place
 * it on.
 */
@QuarkusTest
@Tag("e2e")
class PendingJobServiceE2ETest {

    private static final int NO_LIMIT = 100;
    /**
     * Enough doublings to reach any ceiling worth configuring: the delay grows by 2^(n-1), so the cap
     * is reached by the eighth attempt unless maxBackoff is more than 128 times backoff.
     */
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
        project = fixtures.project("mine");
        alice = fixtures.actor("alice");
        template = fixtures.template("build", null, fixtures.resourceClass("small", null));
    }

    // ---- claiming ----

    @Test
    void claimingTakesTheDueOnesAndMarksThemInFlight() {
        var first = fixtures.queued(project, alice, template, "small");
        var second = fixtures.queued(project, alice, template, "small");

        var claimed = pendingJobService.claimDue(NO_LIMIT);

        assertEquals(List.of(first, second), claimed.stream().map(PendingJobService.Attempt::id).toList());
        assertEquals(PendingJobState.DISPATCHING, stateOf(first));
        assertEquals(PendingJobState.DISPATCHING, stateOf(second));
    }

    /** The attempt is detached, so everything the launch needs has to be copied out of the row. */
    @Test
    void anAttemptCarriesWhatTheLaunchNeeds() {
        var entry = fixtures.queued(project, alice, template, "small");

        var attempt = pendingJobService.claimDue(NO_LIMIT).getFirst();

        assertEquals(entry, attempt.id());
        assertEquals(project, attempt.projectId());
        assertEquals(alice.id(), attempt.requestedBy());
        assertEquals(template, attempt.request().templateId());
        assertEquals("small", attempt.request().resourceClass());
        assertNull(attempt.request().override());
    }

    /** Leaving QUEUED is what stops a second tick from placing the same request twice. */
    @Test
    void claimingTwiceDoesNotHandTheSameOneOut() {
        fixtures.queued(project, alice, template, "small");
        pendingJobService.claimDue(NO_LIMIT);

        assertEquals(List.of(), pendingJobService.claimDue(NO_LIMIT));
    }

    @Test
    void aTickTakesNoMoreThanItsBatch() {
        var first = fixtures.queued(project, alice, template, "small");
        var second = fixtures.queued(project, alice, template, "small");

        var claimed = pendingJobService.claimDue(1);

        assertEquals(List.of(first), claimed.stream().map(PendingJobService.Attempt::id).toList());
        assertEquals(PendingJobState.QUEUED, stateOf(second));
    }

    @Test
    void oneWhoseTurnHasNotComeIsLeftAlone() {
        var now = Instant.now();
        var later = fixtures.queued(project, alice, template, "small",
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(5)));

        assertEquals(List.of(), pendingJobService.claimDue(NO_LIMIT));
        assertEquals(PendingJobState.QUEUED, stateOf(later));
    }

    // ---- settling the attempt ----

    @Test
    void aDispatchedAttemptRecordsTheJobItBecame() {
        var entry = fixtures.queued(project, alice, template, "small");
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
        var entry = fixtures.queued(project, alice, template, "small");
        inFlight(entry);

        pendingJobService.markFailed(entry, "no such template");

        var settled = read(entry);
        assertEquals(PendingJobState.FAILED, settled.getState());
        assertEquals("no such template", settled.getLastError());
        assertEquals(1, settled.getAttempts());
    }

    /**
     * A delete cancels the queue while an attempt may still be in flight, and the finalizer that lands
     * afterwards must not put the entry back in line. Each one writes only to a DISPATCHING row.
     */
    @Test
    void anEntryCancelledMidAttemptStaysCancelled() {
        var dispatched = fixtures.queued(project, alice, template, "small");
        var requeued = fixtures.queued(project, alice, template, "small");
        var failed = fixtures.queued(project, alice, template, "small");
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

    // ---- the backoff ----

    @Test
    void requeueingPutsItBackInLineBehindTheBackoff() {
        var entry = fixtures.queued(project, alice, template, "small");
        inFlight(entry);

        var waited = requeue(entry, "no worker could take the job yet");

        var settled = read(entry);
        assertEquals(PendingJobState.QUEUED, settled.getState());
        assertEquals(1, settled.getAttempts());
        assertEquals("no worker could take the job yet", settled.getLastError());
        waited.assertIs(jobConfig.pending().backoff());
    }

    /** An entry put back in line is not due again until its wait is over. */
    @Test
    void aRequeuedEntryIsNotClaimedStraightBack() {
        var entry = fixtures.queued(project, alice, template, "small");
        inFlight(entry);
        pendingJobService.requeue(entry, "no worker could take the job yet");

        assertEquals(List.of(), pendingJobService.claimDue(NO_LIMIT));
    }

    @Test
    void eachAttemptWaitsLongerThanTheLast() {
        var entry = fixtures.queued(project, alice, template, "small");

        inFlight(entry);
        var first = requeue(entry, "again").atMost();
        inFlight(entry);
        var second = requeue(entry, "again").atMost();

        assertTrue(second.compareTo(first) > 0, second + " should be longer than " + first);
    }

    @Test
    void theBackoffStopsAtItsCeiling() {
        var entry = fixtures.queued(project, alice, template, "small");

        Wait waited = null;
        for (var round = 0; round < ROUNDS_TO_THE_CEILING; round++) {
            inFlight(entry);
            waited = requeue(entry, "again");
        }

        waited.assertIs(jobConfig.pending().maxBackoff());
    }

    // ---- cancelling ----

    @Test
    void aQueuedEntryCanBeCancelled() {
        var entry = fixtures.queued(project, alice, template, "small");

        pendingJobService.cancel(project, entry);

        assertEquals(PendingJobState.CANCELLED, stateOf(entry));
    }

    /** Once claimed it is the dispatcher's to settle; a cancel here would race its finalizer. */
    @Test
    void oneAlreadyInFlightCannotBeCancelled() {
        var entry = fixtures.queued(project, alice, template, "small");
        inFlight(entry);

        var thrown = assertThrows(ClientErrorException.class,
                () -> pendingJobService.cancel(project, entry));

        assertEquals(409, thrown.getResponse().getStatus());
        assertEquals(PendingJobState.DISPATCHING, stateOf(entry));
    }

    @Test
    void anotherProjectsEntryCannotBeCancelledFromHere() {
        var other = fixtures.project("theirs");
        var entry = fixtures.queued(other, alice, template, "small");

        assertThrows(NotFoundException.class, () -> pendingJobService.cancel(project, entry));
        assertEquals(PendingJobState.QUEUED, stateOf(entry));
    }

    @Test
    void anEntryIsOnlyFoundThroughItsOwnProject() {
        var other = fixtures.project("theirs");
        var entry = fixtures.queued(other, alice, template, "small");

        assertTrue(inTx(() -> pendingJobService.findInProject(other, entry)).isPresent());
        assertTrue(inTx(() -> pendingJobService.findInProject(project, entry)).isEmpty());
    }

    // ---- a tick with nothing to place ----

    /** Expiry runs before the dispatcher gives up on the tick, so a queue ages out either way. */
    @Test
    void theQueueStillAgesOutWhileNoWorkerIsConnected() {
        var now = Instant.now();
        var overdue = fixtures.queued(project, alice, template, "small",
                now.minus(Duration.ofMinutes(1)), now);

        dispatcher.tick();

        assertEquals(PendingJobState.EXPIRED, stateOf(overdue));
    }

    @Test
    void nothingIsClaimedWhileNoWorkerIsConnected() {
        var entry = fixtures.queued(project, alice, template, "small");
        // Stated rather than assumed: the roster is JVM-wide, so a test that left a session open would
        // otherwise turn this into a dispatch attempt and fail somewhere else entirely.
        assertFalse(workerService.hasSchedulableWorker());

        dispatcher.tick();

        assertEquals(PendingJobState.QUEUED, stateOf(entry));
    }

    /** Where {@code claimDue} would have left it, without needing the entry to be due again. */
    private void inFlight(UUID id) {
        inTx(() -> PendingJob.<PendingJob>findById(id).setState(PendingJobState.DISPATCHING));
    }

    /** Requeues the entry and returns the window the wait it imposed must lie in. */
    private Wait requeue(UUID id, String reason) {
        // Truncated down, so the microsecond precision of a timestamp column cannot round the stored
        // next_attempt_at below this bound.
        var before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        pendingJobService.requeue(id, reason);
        var after = Instant.now();

        var next = read(id).getNextAttemptAt();
        return new Wait(Duration.between(after, next), Duration.between(before, next));
    }

    /** The service read its own clock between the two readings, so the wait it applied lies between. */
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
