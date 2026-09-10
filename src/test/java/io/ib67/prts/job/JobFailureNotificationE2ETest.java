package io.ib67.prts.job;

import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.notification.entity.Notification;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.inTx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the message {@link JobService#applyState} leaves the requester of a job that failed.
 */
@QuarkusTest
@Tag("e2e")
class JobFailureNotificationE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;
    @Inject
    JobService jobService;

    private UUID project;
    private ResourceClass klass;
    private Fixtures.Actor requester;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.createProject("mine");
        klass = fixtures.createResourceClass("small", project);
        requester = fixtures.createActor("requester");
    }

    @Test
    void aFailedJobTellsWhoAskedForIt() {
        var job = fixtures.createJob(project, requester, klass, JobState.RUNNING, null);

        jobService.applyState(job, JobState.FAILED);

        var left = messagesFor(requester.id());
        assertEquals(1, left.size());
        assertEquals("Job failed in mine", left.getFirst().getTitle());
        assertTrue(left.getFirst().getContent().contains(job.toString()), left.getFirst().getContent());
    }

    @Test
    void aJobThatSucceedsSaysNothing() {
        var job = fixtures.createJob(project, requester, klass, JobState.RUNNING, null);

        jobService.applyState(job, JobState.SUCCESS);

        assertEquals(List.of(), messagesFor(requester.id()));
    }

    /**
     * {@code requested_by} carries no foreign key, so the requester may be gone by the time the job
     * fails — and the transition must land anyway.
     */
    @Test
    void aRequesterWhoNoLongerExistsStillLetsTheJobFail() {
        var stranger = new Fixtures.Actor(UUID.randomUUID(), "");
        var job = fixtures.createJob(project, stranger, klass, JobState.RUNNING, null);

        jobService.applyState(job, JobState.FAILED);

        assertEquals(JobState.FAILED, inTx(() -> Job.<Job>findById(job).getState()));
        assertTrue(inTx(() -> Notification.<Notification>listAll()).isEmpty(), "nobody was written to");
    }

    /** Nobody signs in as a sub-account, so its failures reach the person who created it. */
    @Test
    void aSubAccountsFailureReachesItsCreator() {
        var ci = fixtures.createSubAccount(project, "ci", requester);
        var job = fixtures.createJob(project, ci, klass, JobState.RUNNING, null);

        jobService.applyState(job, JobState.FAILED);

        assertEquals(List.of(), messagesFor(ci.id()));
        var left = messagesFor(requester.id());
        assertEquals(1, left.size());
        assertTrue(left.getFirst().getSender().contains("ci"), left.getFirst().getSender());
    }

    private static List<Notification> messagesFor(UUID recipient) {
        return inTx(() -> Notification.listByRecipient(recipient, 0, 10));
    }
}
