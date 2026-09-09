package io.ib67.prts.agent.job.entity;

import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.inTx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link JobLock#tryAcquire} and lock takeover semantics.
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
        project = fixtures.createProject("mine");
        alice = fixtures.createActor("alice");
        small = fixtures.createResourceClass("small", null);
    }

    /** A job that already holds a lock can re-acquire it. */
    @Test
    void aFreeNameIsAcquiredAndTheHolderAskingAgainStillHoldsIt() {
        var job = job(JobState.RUNNING);

        assertTrue(acquire("deploy", job));
        assertEquals(job, holderOf("deploy"));

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
     * A lock held by a finished job (e.g. after a crash without cleanup) can be taken over by another job.
     */
    @Test
    void aNameHeldByAFinishedJobIsTakenOverWhateverItFinishedAs() {
        for (var state : new JobState[]{JobState.SUCCESS, JobState.FAILED, JobState.CANCELLED}) {
            // Simulate a completed job whose lock was not released.
            var holder = job(JobState.RUNNING);
            var contender = job(JobState.PENDING);
            acquire(state.name(), holder);
            end(holder, state);

            assertTrue(acquire(state.name(), contender), "a " + state + " holder should let go");
            assertEquals(contender, holderOf(state.name()));
        }
    }

    /** Locks are scoped to (project_id, name), allowing different projects to use the same lock name. */
    @Test
    void theSameNameInAnotherProjectIsFree() {
        var other = fixtures.createProject("theirs");
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

    @Test
    void releasingByAJobThatHoldsNothingLeavesTheLockStanding() {
        var holder = job(JobState.RUNNING);
        var other = job(JobState.RUNNING);
        acquire("deploy", holder);

        inTx(() -> JobLock.releaseBy(other));

        assertEquals(holder, holderOf("deploy"));
    }

    /**
     * Verifies the unique constraint on job_id ensuring a job holds at most one lock.
     */
    @Test
    void aJobHoldsAtMostOneName() {
        var holder = job(JobState.RUNNING);
        assertTrue(acquire("deploy", holder));

        var thrown = assertThrows(RuntimeException.class, () -> acquire("publish", holder));
        assertTrue(causedByConstraintViolation(thrown), () -> "expected a unique violation, got " + thrown);
        assertEquals(holder, holderOf("deploy"));
        assertNull(holderOf("publish"));
    }

    private static boolean causedByConstraintViolation(Throwable thrown) {
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

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
        return fixtures.createJob(projectId, alice, small, state, UUID.randomUUID());
    }

    private void end(UUID jobId, JobState terminal) {
        inTx(() -> Job.<Job>findById(jobId).transitionTo(terminal));
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
