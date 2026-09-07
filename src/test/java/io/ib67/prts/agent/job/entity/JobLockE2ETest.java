package io.ib67.prts.agent.job.entity;

import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobState;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.inTx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JobLock#tryAcquire} under contention.
 *
 * <p>Each attempt runs in a committed transaction of its own, which is how {@code JobLauncher} calls it
 * and the only way the second attempt can see what the first left behind.
 */
@QuarkusTest
@Tag("e2e")
class JobLockE2ETest {

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
    void aFreeNameIsAcquired() {
        var job = job(JobState.RUNNING);

        assertTrue(acquire("deploy", job));
        assertEquals(job, holderOf("deploy"));
    }

    /** Re-entrant on purpose: a retry of the same job must not deadlock itself out. */
    @Test
    void theHolderAskingAgainStillHoldsIt() {
        var job = job(JobState.RUNNING);
        acquire("deploy", job);

        assertTrue(acquire("deploy", job));
        assertEquals(job, holderOf("deploy"));
    }

    @Test
    void anotherJobCannotTakeANameAliveJobHolds() {
        var holder = job(JobState.RUNNING);
        var contender = job(JobState.PENDING);
        acquire("deploy", holder);

        assertFalse(acquire("deploy", contender));
        assertEquals(holder, holderOf("deploy"));
    }

    /**
     * A holder that finished without releasing — a crash between completion and cleanup — must not
     * wedge the name forever, so the next comer takes it over rather than waiting.
     */
    @Test
    void aNameHeldByAFinishedJobIsTakenOver() {
        var holder = job(JobState.RUNNING);
        var contender = job(JobState.PENDING);
        acquire("deploy", holder);
        complete(holder);

        assertTrue(acquire("deploy", contender));
        assertEquals(contender, holderOf("deploy"));
    }

    @Test
    void everyTerminalStateCountsAsFinished() {
        for (var state : new JobState[]{JobState.SUCCESS, JobState.FAILED, JobState.CANCELLED}) {
            var holder = job(state);
            var contender = job(JobState.RUNNING);
            acquire(state.name(), holder);

            assertTrue(acquire(state.name(), contender), "a " + state + " holder should let go");
        }
    }

    /** The key is (project, name), so two projects deploying at once do not queue behind each other. */
    @Test
    void theSameNameInAnotherProjectIsFree() {
        var other = fixtures.project("theirs");
        var mine = job(JobState.RUNNING);
        var theirs = jobIn(other, JobState.RUNNING);
        acquire("deploy", mine);

        assertTrue(acquire("deploy", theirs));
        assertEquals(mine, holderOf(project, "deploy"));
        assertEquals(theirs, holderOf(other, "deploy"));
    }

    @Test
    void releasingLetsTheNextJobIn() {
        var holder = job(JobState.RUNNING);
        var contender = job(JobState.RUNNING);
        acquire("deploy", holder);

        inTx(() -> JobLock.releaseBy(holder));

        assertNull(holderOf("deploy"));
        assertTrue(acquire("deploy", contender));
    }

    /** Release is by job, not by name: a job holding nothing releases nothing. */
    @Test
    void releasingByAJobThatHoldsNothingLeavesTheLockStanding() {
        var holder = job(JobState.RUNNING);
        var other = job(JobState.RUNNING);
        acquire("deploy", holder);

        inTx(() -> JobLock.releaseBy(other));

        assertEquals(holder, holderOf("deploy"));
    }

    @Test
    void releasingFreesEveryNameTheJobHolds() {
        var holder = job(JobState.RUNNING);
        acquire("deploy", holder);
        acquire("publish", holder);

        inTx(() -> JobLock.releaseBy(holder));

        assertNull(holderOf("deploy"));
        assertNull(holderOf("publish"));
    }

    /** There is no project to scope the key to, so the attempt fails rather than guessing one. */
    @Test
    void aJobThatDoesNotExistAcquiresNothing() {
        assertFalse(acquire("deploy", UUID.randomUUID()));
        assertNull(holderOf("deploy"));
    }

    private boolean acquire(String name, UUID jobId) {
        return inTx(() -> JobLock.tryAcquire(name, jobId));
    }

    private UUID job(JobState state) {
        return jobIn(project, state);
    }

    private UUID jobIn(UUID projectId, JobState state) {
        return fixtures.job(projectId, alice, small, state, UUID.randomUUID());
    }

    private void complete(UUID jobId) {
        inTx(() -> Job.<Job>findById(jobId).transitionTo(JobState.SUCCESS));
    }

    private UUID holderOf(String name) {
        return holderOf(project, name);
    }

    private UUID holderOf(UUID projectId, String name) {
        return inTx(() -> {
            var lock = JobLock.<JobLock>findById(new JobLock.Id(projectId, name));
            return lock == null ? null : lock.getJob().getId();
        });
    }
}
