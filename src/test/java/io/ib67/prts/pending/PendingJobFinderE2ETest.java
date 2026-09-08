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
 * Database queries and status transitions for {@link PendingJob}.
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
        project = fixtures.createProject("mine");
        alice = fixtures.createActor("alice");
        template = fixtures.createTemplate("build", null, fixtures.createResourceClass("small", null));
    }

    @Test
    void oneWhoseTurnHasNotComeIsNotDue() {
        var now = Instant.now();
        fixtures.createQueuedJob(project, alice, template, "small",
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(5)));

        assertEquals(List.of(), due(now));
    }

    @Test
    void aQueuedOneWhoseTurnHasComeIsDue() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");

        assertEquals(List.of(entry), due(Instant.now()));
    }

    /** Only QUEUED jobs are claimable; in-flight DISPATCHING jobs are excluded. */
    @Test
    void oneAlreadyBeingDispatchedIsNotDue() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        setState(entry, PendingJobState.DISPATCHING);

        assertEquals(List.of(), due(Instant.now()));
    }

    @Test
    void aSettledOneIsNeverDue() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        setState(entry, PendingJobState.CANCELLED);

        assertEquals(List.of(), due(Instant.now()));
    }

    /** Due jobs are returned in FIFO order (oldest first). */
    @Test
    void theDueListIsOldestFirst() {
        var first = fixtures.createQueuedJob(project, alice, template, "small");
        var second = fixtures.createQueuedJob(project, alice, template, "small");
        var third = fixtures.createQueuedJob(project, alice, template, "small");

        assertEquals(List.of(first, second, third), due(Instant.now()));
    }

    @Test
    void theDueListRespectsItsLimit() {
        var first = fixtures.createQueuedJob(project, alice, template, "small");
        fixtures.createQueuedJob(project, alice, template, "small");

        assertEquals(List.of(first),
                inTx(() -> PendingJob.listDue(Instant.now(), 1).stream()
                        .map(PendingJob::getId).toList()));
    }

    /** The due queue spans across all projects. */
    @Test
    void theDueListCrossesProjects() {
        var other = fixtures.createProject("theirs");
        fixtures.createQueuedJob(project, alice, template, "small");
        fixtures.createQueuedJob(other, alice, template, "small");

        assertEquals(2, due(Instant.now()).size());
    }

    @Test
    void theUnplacedListIsNewestFirst() {
        var first = fixtures.createQueuedJob(project, alice, template, "small");
        var second = fixtures.createQueuedJob(project, alice, template, "small");

        assertEquals(List.of(second, first), unplaced());
    }

    /** Once an entry is associated with a job (jobId != null), it is no longer considered unplaced. */
    @Test
    void oneThatBecameAJobDropsOut() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        inTx(() -> PendingJob.<PendingJob>findById(entry).setJobId(UUID.randomUUID()));

        assertEquals(List.of(), unplaced());
    }

    /** Settled entries without a jobId remain in the unplaced list. */
    @Test
    void aSettledOneWithNoJobStaysOnTheList() {
        var entry = fixtures.createQueuedJob(project, alice, template, "small");
        setState(entry, PendingJobState.EXPIRED);

        assertEquals(List.of(entry), unplaced());
    }

    @Test
    void theUnplacedListHoldsOnlyThisProject() {
        var other = fixtures.createProject("theirs");
        var mine = fixtures.createQueuedJob(project, alice, template, "small");
        fixtures.createQueuedJob(other, alice, template, "small");

        assertEquals(List.of(mine), unplaced());
    }

    @Test
    void theActiveCountHoldsQueuedAndDispatching() {
        fixtures.createQueuedJob(project, alice, template, "small");
        setState(fixtures.createQueuedJob(project, alice, template, "small"), PendingJobState.DISPATCHING);
        setState(fixtures.createQueuedJob(project, alice, template, "small"), PendingJobState.DISPATCHED);
        setState(fixtures.createQueuedJob(project, alice, template, "small"), PendingJobState.CANCELLED);
        setState(fixtures.createQueuedJob(project, alice, template, "small"), PendingJobState.EXPIRED);
        setState(fixtures.createQueuedJob(project, alice, template, "small"), PendingJobState.FAILED);

        assertEquals(2L, (long) inTx(() -> PendingJob.countActive(project)));
    }

    @Test
    void theActiveCountIgnoresOtherProjects() {
        var other = fixtures.createProject("theirs");
        fixtures.createQueuedJob(other, alice, template, "small");

        assertEquals(0L, (long) inTx(() -> PendingJob.countActive(project)));
    }

    @Test
    void cancellingActiveLeavesTheSettledAlone() {
        var queued = fixtures.createQueuedJob(project, alice, template, "small");
        var dispatching = fixtures.createQueuedJob(project, alice, template, "small");
        setState(dispatching, PendingJobState.DISPATCHING);
        var expired = fixtures.createQueuedJob(project, alice, template, "small");
        setState(expired, PendingJobState.EXPIRED);

        assertEquals(2, (int) inTx(() -> PendingJob.cancelActive(project)));

        assertEquals(PendingJobState.CANCELLED, stateOf(queued));
        assertEquals(PendingJobState.CANCELLED, stateOf(dispatching));
        assertEquals(PendingJobState.EXPIRED, stateOf(expired));
    }

    @Test
    void cancellingActiveIgnoresOtherProjects() {
        var other = fixtures.createProject("theirs");
        var theirs = fixtures.createQueuedJob(other, alice, template, "small");

        assertEquals(0, (int) inTx(() -> PendingJob.cancelActive(project)));
        assertEquals(PendingJobState.QUEUED, stateOf(theirs));
    }

    /** Overdue expiration applies strictly to entries whose expiresAt is before the specified cutoff. */
    @Test
    void expiringTakesOnlyTheQueuedOnesPastTheirTime() {
        // Truncate to millisecond precision to align with database timestamp storage.
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var overdue = fixtures.createQueuedJob(project, alice, template, "small",
                now.minus(Duration.ofMinutes(1)), now);
        var onTheDot = fixtures.createQueuedJob(project, alice, template, "small", now, now);
        var later = fixtures.createQueuedJob(project, alice, template, "small",
                now.plus(Duration.ofHours(1)), now);
        var dispatching = fixtures.createQueuedJob(project, alice, template, "small",
                now.minus(Duration.ofMinutes(1)), now);
        setState(dispatching, PendingJobState.DISPATCHING);

        assertEquals(1, (int) inTx(() -> PendingJob.expireOverdue(now)));

        assertEquals(PendingJobState.EXPIRED, stateOf(overdue));
        assertEquals(PendingJobState.QUEUED, stateOf(onTheDot));
        assertEquals(PendingJobState.QUEUED, stateOf(later));
        // Entries currently dispatching are not expired by the cleanup query.
        assertEquals(PendingJobState.DISPATCHING, stateOf(dispatching));
    }

    /** Startup reset recovers stranded DISPATCHING entries back to QUEUED. */
    @Test
    void resettingRequeuesEveryDispatchingEntry() {
        var dispatching = fixtures.createQueuedJob(project, alice, template, "small");
        setState(dispatching, PendingJobState.DISPATCHING);
        var dispatched = fixtures.createQueuedJob(project, alice, template, "small");
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
