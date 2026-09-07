package io.ib67.prts.pending;

import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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

/**
 * {@link PendingJob}'s finders. Each has its own idea of "still in play", and they do not agree:
 * {@code listDue} wants QUEUED and due, {@code countActive} counts DISPATCHING too, and
 * {@code listUnplacedByProject} goes by whether a {@code Job} came of it rather than by state at all.
 */
@QuarkusTest
@Tag("e2e")
class PendingJobFinderE2ETest {

    private static final int NO_LIMIT = 100;

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

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

    // ---- the dispatcher's view ----

    @Test
    void oneWhoseTurnHasNotComeIsNotDue() {
        var now = Instant.now();
        fixtures.queued(project, alice, template, "small",
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(5)));

        assertEquals(List.of(), due(now));
    }

    @Test
    void aQueuedOneWhoseTurnHasComeIsDue() {
        var entry = fixtures.queued(project, alice, template, "small");

        assertEquals(List.of(entry), due(Instant.now()));
    }

    /** Only QUEUED is claimable; a claim already in flight must not be handed out twice. */
    @Test
    void oneAlreadyBeingDispatchedIsNotDue() {
        var entry = fixtures.queued(project, alice, template, "small");
        setState(entry, PendingJobState.DISPATCHING);

        assertEquals(List.of(), due(Instant.now()));
    }

    @Test
    void aSettledOneIsNeverDue() {
        var entry = fixtures.queued(project, alice, template, "small");
        setState(entry, PendingJobState.CANCELLED);

        assertEquals(List.of(), due(Instant.now()));
    }

    /** The queue is a queue: oldest first, which is the reverse of what a reader is shown. */
    @Test
    void theDueListIsOldestFirst() {
        var first = fixtures.queued(project, alice, template, "small");
        var second = fixtures.queued(project, alice, template, "small");
        var third = fixtures.queued(project, alice, template, "small");

        assertEquals(List.of(first, second, third), due(Instant.now()));
    }

    @Test
    void theDueListRespectsItsLimit() {
        var first = fixtures.queued(project, alice, template, "small");
        fixtures.queued(project, alice, template, "small");

        assertEquals(List.of(first),
                inTx(() -> PendingJob.listDue(Instant.now(), 1).stream()
                        .map(PendingJob::getId).toList()));
    }

    /** The dispatcher serves every project from one queue, so nothing scopes this finder. */
    @Test
    void theDueListCrossesProjects() {
        var other = fixtures.project("theirs");
        fixtures.queued(project, alice, template, "small");
        fixtures.queued(other, alice, template, "small");

        assertEquals(2, due(Instant.now()).size());
    }

    // ---- the reader's view ----

    @Test
    void theUnplacedListIsNewestFirst() {
        var first = fixtures.queued(project, alice, template, "small");
        var second = fixtures.queued(project, alice, template, "small");

        assertEquals(List.of(second, first), unplaced());
    }

    /**
     * Once a queue entry has become a job the job itself is what a reader is shown, so the entry drops
     * out — by {@code jobId}, not by state, so a DISPATCHED entry that somehow kept no job would stay.
     */
    @Test
    void oneThatBecameAJobDropsOut() {
        var entry = fixtures.queued(project, alice, template, "small");
        inTx(() -> PendingJob.<PendingJob>findById(entry).setJobId(UUID.randomUUID()));

        assertEquals(List.of(), unplaced());
    }

    /** A cancelled entry still holds no job, so it stays on the list for the reader to see. */
    @Test
    void aSettledOneWithNoJobStaysOnTheList() {
        var entry = fixtures.queued(project, alice, template, "small");
        setState(entry, PendingJobState.EXPIRED);

        assertEquals(List.of(entry), unplaced());
    }

    @Test
    void theUnplacedListHoldsOnlyThisProject() {
        var other = fixtures.project("theirs");
        var mine = fixtures.queued(project, alice, template, "small");
        fixtures.queued(other, alice, template, "small");

        assertEquals(List.of(mine), unplaced());
    }

    // ---- the quota's view ----

    @Test
    void theActiveCountHoldsQueuedAndDispatching() {
        fixtures.queued(project, alice, template, "small");
        setState(fixtures.queued(project, alice, template, "small"), PendingJobState.DISPATCHING);
        setState(fixtures.queued(project, alice, template, "small"), PendingJobState.DISPATCHED);
        setState(fixtures.queued(project, alice, template, "small"), PendingJobState.CANCELLED);
        setState(fixtures.queued(project, alice, template, "small"), PendingJobState.EXPIRED);
        setState(fixtures.queued(project, alice, template, "small"), PendingJobState.FAILED);

        assertEquals(2L, (long) inTx(() -> PendingJob.countActive(project)));
    }

    @Test
    void theActiveCountIgnoresOtherProjects() {
        var other = fixtures.project("theirs");
        fixtures.queued(other, alice, template, "small");

        assertEquals(0L, (long) inTx(() -> PendingJob.countActive(project)));
    }

    // ---- the bulk updates ----

    @Test
    void cancellingActiveLeavesTheSettledAlone() {
        var queued = fixtures.queued(project, alice, template, "small");
        var dispatching = fixtures.queued(project, alice, template, "small");
        setState(dispatching, PendingJobState.DISPATCHING);
        var expired = fixtures.queued(project, alice, template, "small");
        setState(expired, PendingJobState.EXPIRED);

        assertEquals(2, (int) inTx(() -> PendingJob.cancelActive(project)));

        assertEquals(PendingJobState.CANCELLED, stateOf(queued));
        assertEquals(PendingJobState.CANCELLED, stateOf(dispatching));
        assertEquals(PendingJobState.EXPIRED, stateOf(expired));
    }

    @Test
    void cancellingActiveIgnoresOtherProjects() {
        var other = fixtures.project("theirs");
        var theirs = fixtures.queued(other, alice, template, "small");

        assertEquals(0, (int) inTx(() -> PendingJob.cancelActive(project)));
        assertEquals(PendingJobState.QUEUED, stateOf(theirs));
    }

    /** Strictly past: an entry expiring exactly now has not run out of time yet. */
    @Test
    void expiringTakesOnlyTheQueuedOnesPastTheirTime() {
        // Truncated, so the boundary case really is equal: a timestamp column keeps microseconds and
        // would otherwise round the stored value below the nanosecond-precision parameter.
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var overdue = fixtures.queued(project, alice, template, "small",
                now.minus(Duration.ofMinutes(1)), now);
        var onTheDot = fixtures.queued(project, alice, template, "small", now, now);
        var later = fixtures.queued(project, alice, template, "small",
                now.plus(Duration.ofHours(1)), now);
        var dispatching = fixtures.queued(project, alice, template, "small",
                now.minus(Duration.ofMinutes(1)), now);
        setState(dispatching, PendingJobState.DISPATCHING);

        assertEquals(1, (int) inTx(() -> PendingJob.expireOverdue(now)));

        assertEquals(PendingJobState.EXPIRED, stateOf(overdue));
        assertEquals(PendingJobState.QUEUED, stateOf(onTheDot));
        assertEquals(PendingJobState.QUEUED, stateOf(later));
        // An attempt already in flight is the dispatcher's to settle, not the reaper's.
        assertEquals(PendingJobState.DISPATCHING, stateOf(dispatching));
    }

    /** After a restart nothing is in flight any more, whatever the rows say. */
    @Test
    void resettingRequeuesEveryDispatchingEntry() {
        var dispatching = fixtures.queued(project, alice, template, "small");
        setState(dispatching, PendingJobState.DISPATCHING);
        var dispatched = fixtures.queued(project, alice, template, "small");
        setState(dispatched, PendingJobState.DISPATCHED);

        assertEquals(1, (int) inTx(PendingJob::resetDispatching));

        assertEquals(PendingJobState.QUEUED, stateOf(dispatching));
        assertEquals(PendingJobState.DISPATCHED, stateOf(dispatched));
    }

    private List<UUID> due(Instant now) {
        return inTx(() -> PendingJob.listDue(now, NO_LIMIT).stream().map(PendingJob::getId).toList());
    }

    private List<UUID> unplaced() {
        return inTx(() -> PendingJob.listUnplacedByProject(project, NO_LIMIT).stream()
                .map(PendingJob::getId).toList());
    }

    private void setState(UUID id, PendingJobState state) {
        inTx(() -> PendingJob.<PendingJob>findById(id).setState(state));
    }

    private PendingJobState stateOf(UUID id) {
        return inTx(() -> PendingJob.<PendingJob>findById(id).getState());
    }
}
