package io.ib67.prts.project.entity;

import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.inTx;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Job}'s finders, and above all the rule they share: a job that is still {@code PENDING} and has
 * no worker has not been placed anywhere yet, so nobody is shown it.
 */
@QuarkusTest
@Tag("e2e")
class JobFinderE2ETest {

    private static final int NO_LIMIT = 100;

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private UUID project;
    private Fixtures.Actor alice;
    private ResourceClass small;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.project("mine");
        alice = fixtures.actor("alice");
        small = fixtures.resourceClass("small", null);
    }

    @Test
    void anUnplacedJobIsHidden() {
        fixtures.job(project, alice, small, JobState.PENDING, null);

        assertEquals(List.of(), visible());
    }

    @Test
    void aPendingJobThatHasAWorkerIsVisible() {
        var job = fixtures.job(project, alice, small, JobState.PENDING, UUID.randomUUID());

        assertEquals(List.of(job), visible());
    }

    /** Once it has left PENDING the worker no longer matters: a job that failed to place is still news. */
    @Test
    void aSettledJobWithNoWorkerIsVisible() {
        var job = fixtures.job(project, alice, small, JobState.FAILED, null);

        assertEquals(List.of(job), visible());
    }

    @Test
    void theListIsNewestFirst() {
        var first = fixtures.job(project, alice, small, JobState.SUCCESS, null);
        var second = fixtures.job(project, alice, small, JobState.SUCCESS, null);
        var third = fixtures.job(project, alice, small, JobState.SUCCESS, null);

        // createdAt then id, both descending; the ids are UUIDv7 and so ascend with time either way.
        assertEquals(List.of(third, second, first), visible());
    }

    @Test
    void theLimitCapsTheList() {
        fixtures.job(project, alice, small, JobState.SUCCESS, null);
        var second = fixtures.job(project, alice, small, JobState.SUCCESS, null);

        assertEquals(List.of(second),
                inTx(() -> Job.listVisibleByProject(project, 1).stream().map(Job::getId).toList()));
    }

    @Test
    void theListHoldsOnlyThisProject() {
        var other = fixtures.project("theirs");
        var mine = fixtures.job(project, alice, small, JobState.SUCCESS, null);
        fixtures.job(other, alice, small, JobState.SUCCESS, null);

        assertEquals(List.of(mine), visible());
    }

    /**
     * The two counts differ in what they ignore: {@code visible} skips the unplaced,
     * {@code running} skips everything that is not on a worker and still open.
     */
    @Test
    void theCountsSeparateVisibleFromRunning() {
        fixtures.job(project, alice, small, JobState.PENDING, null);
        fixtures.job(project, alice, small, JobState.PENDING, UUID.randomUUID());
        fixtures.job(project, alice, small, JobState.RUNNING, UUID.randomUUID());
        fixtures.job(project, alice, small, JobState.SUCCESS, null);

        var counts = inTx(() -> Job.countByProject(project));

        assertEquals(3, counts.visible());
        assertEquals(2, counts.running());
    }

    @Test
    void theCountsIgnoreOtherProjects() {
        var other = fixtures.project("theirs");
        fixtures.job(other, alice, small, JobState.RUNNING, UUID.randomUUID());

        var counts = inTx(() -> Job.countByProject(project));

        assertEquals(0, counts.visible());
        assertEquals(0, counts.running());
    }

    /** Open means uncompleted, whether or not anything is carrying it. */
    @Test
    void theOpenListHoldsPendingAndRunningOnly() {
        var pending = fixtures.job(project, alice, small, JobState.PENDING, null);
        var running = fixtures.job(project, alice, small, JobState.RUNNING, UUID.randomUUID());
        fixtures.job(project, alice, small, JobState.SUCCESS, null);
        fixtures.job(project, alice, small, JobState.FAILED, null);
        fixtures.job(project, alice, small, JobState.CANCELLED, null);

        // No order by on the finder, so only membership is asserted.
        var open = inTx(() -> Job.listOpenByProject(project).stream().map(Job::getId).toList());

        assertEquals(Set.of(pending, running), Set.copyOf(open));
    }

    @Test
    void theOpenListOfAWorkerIgnoresOtherWorkers() {
        var worker = UUID.randomUUID();
        var mine = fixtures.job(project, alice, small, JobState.RUNNING, worker);
        fixtures.job(project, alice, small, JobState.RUNNING, UUID.randomUUID());
        fixtures.job(project, alice, small, JobState.SUCCESS, worker);

        assertEquals(List.of(mine),
                inTx(() -> Job.listOpenByWorker(worker).stream().map(Job::getId).toList()));
    }

    /** A worker's open jobs are its own across every project, since that is what a disconnect fails. */
    @Test
    void theOpenListOfAWorkerCrossesProjects() {
        var other = fixtures.project("theirs");
        var worker = UUID.randomUUID();
        fixtures.job(project, alice, small, JobState.RUNNING, worker);
        fixtures.job(other, alice, small, JobState.PENDING, worker);

        assertEquals(2, inTx(() -> Job.listOpenByWorker(worker).size()).intValue());
    }

    private List<UUID> visible() {
        return inTx(() -> Job.listVisibleByProject(project, NO_LIMIT).stream().map(Job::getId).toList());
    }
}
