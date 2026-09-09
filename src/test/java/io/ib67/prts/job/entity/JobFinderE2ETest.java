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
 * Tests query finders and visibility filtering on {@link Job}.
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
        project = fixtures.createProject("mine");
        alice = fixtures.createActor("alice");
        small = fixtures.createResourceClass("small", null);
    }

    @Test
    void anUnplacedJobIsHidden() {
        fixtures.createJob(project, alice, small, JobState.PENDING, null);

        assertEquals(List.of(), visible());
    }

    @Test
    void aPendingJobThatHasAWorkerIsVisible() {
        var job = fixtures.createJob(project, alice, small, JobState.PENDING, UUID.randomUUID());

        assertEquals(List.of(job), visible());
    }

    /** Settled jobs (e.g. FAILED) without a worker remain visible. */
    @Test
    void aSettledJobWithNoWorkerIsVisible() {
        var job = fixtures.createJob(project, alice, small, JobState.FAILED, null);

        assertEquals(List.of(job), visible());
    }

    @Test
    void theListIsNewestFirst() {
        var first = fixtures.createJob(project, alice, small, JobState.SUCCESS, null);
        var second = fixtures.createJob(project, alice, small, JobState.SUCCESS, null);
        var third = fixtures.createJob(project, alice, small, JobState.SUCCESS, null);

        assertEquals(List.of(third, second, first), visible());
    }

    @Test
    void theLimitCapsTheList() {
        fixtures.createJob(project, alice, small, JobState.SUCCESS, null);
        var second = fixtures.createJob(project, alice, small, JobState.SUCCESS, null);

        assertEquals(List.of(second),
                inTx(() -> Job.listVisibleByProject(project, 1).stream().map(Job::getId).toList()));
    }

    @Test
    void theListHoldsOnlyThisProject() {
        var other = fixtures.createProject("theirs");
        var mine = fixtures.createJob(project, alice, small, JobState.SUCCESS, null);
        fixtures.createJob(other, alice, small, JobState.SUCCESS, null);

        assertEquals(List.of(mine), visible());
    }

    /**
     * Verifies that countByProject accurately counts visible vs running jobs.
     */
    @Test
    void theCountsSeparateVisibleFromRunning() {
        fixtures.createJob(project, alice, small, JobState.PENDING, null);
        fixtures.createJob(project, alice, small, JobState.PENDING, UUID.randomUUID());
        fixtures.createJob(project, alice, small, JobState.RUNNING, UUID.randomUUID());
        fixtures.createJob(project, alice, small, JobState.SUCCESS, null);

        var counts = inTx(() -> Job.countByProject(project));

        assertEquals(3, counts.visible());
        assertEquals(2, counts.running());
    }

    @Test
    void theCountsIgnoreOtherProjects() {
        var other = fixtures.createProject("theirs");
        fixtures.createJob(other, alice, small, JobState.RUNNING, UUID.randomUUID());

        var counts = inTx(() -> Job.countByProject(project));

        assertEquals(0, counts.visible());
        assertEquals(0, counts.running());
    }

    /** Open jobs include only PENDING and RUNNING states. */
    @Test
    void theOpenListHoldsPendingAndRunningOnly() {
        var pending = fixtures.createJob(project, alice, small, JobState.PENDING, null);
        var running = fixtures.createJob(project, alice, small, JobState.RUNNING, UUID.randomUUID());
        fixtures.createJob(project, alice, small, JobState.SUCCESS, null);
        fixtures.createJob(project, alice, small, JobState.FAILED, null);
        fixtures.createJob(project, alice, small, JobState.CANCELLED, null);

        var open = inTx(() -> Job.listOpenByProject(project).stream().map(Job::getId).toList());

        assertEquals(Set.of(pending, running), Set.copyOf(open));
    }

    @Test
    void theOpenListOfAWorkerIgnoresOtherWorkers() {
        var worker = UUID.randomUUID();
        var mine = fixtures.createJob(project, alice, small, JobState.RUNNING, worker);
        fixtures.createJob(project, alice, small, JobState.RUNNING, UUID.randomUUID());
        fixtures.createJob(project, alice, small, JobState.SUCCESS, worker);

        assertEquals(List.of(mine),
                inTx(() -> Job.listOpenByWorker(worker).stream().map(Job::getId).toList()));
    }

    /** listOpenByWorker returns open jobs for the given worker across all projects. */
    @Test
    void theOpenListOfAWorkerCrossesProjects() {
        var other = fixtures.createProject("theirs");
        var worker = UUID.randomUUID();
        fixtures.createJob(project, alice, small, JobState.RUNNING, worker);
        fixtures.createJob(other, alice, small, JobState.PENDING, worker);

        assertEquals(2, inTx(() -> Job.listOpenByWorker(worker).size()).intValue());
    }

    private List<UUID> visible() {
        return inTx(() -> Job.listVisibleByProject(project, NO_LIMIT).stream().map(Job::getId).toList());
    }
}
