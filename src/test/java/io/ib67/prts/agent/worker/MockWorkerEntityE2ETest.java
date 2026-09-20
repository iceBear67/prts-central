package io.ib67.prts.agent.worker;

import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.job.JobLauncher;
import io.ib67.prts.job.JobService;
import io.ib67.prts.job.entity.Artifact;
import io.ib67.prts.job.entity.Job;
import io.ib67.prts.job.entity.JobLog;
import io.ib67.prts.job.entity.JobRequest;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.ib67.prts.worker.mock.JobRun;
import io.ib67.prts.worker.mock.JobScript;
import io.ib67.prts.worker.mock.MockWorker;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives a whole job through {@code worker-mock}: the control plane places it, the mock runs it, and
 * everything the mock reports — its state, its logs, its artifacts, its volumes — lands where it
 * should.
 *
 * <p>This is the counterpart to the mock's own tests. Those pin the wire format and the script
 * behaviour; this one is the only place where the mock meets the real control plane, which is what
 * says the two agree.
 */
@QuarkusTest
@Tag("e2e")
class MockWorkerEntityE2ETest {

    private static final String SECRET = "test-worker-secret";
    private static final Duration SETTLE = Duration.ofSeconds(10);
    /** An upload is promoted by a sweeper that runs every two seconds. */
    private static final Duration PROMOTE = Duration.ofSeconds(30);

    @TestHTTPResource
    URI baseUri;

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;
    @Inject
    JobLauncher launcher;
    @Inject
    JobService jobService;
    @Inject
    WorkerService workerService;
    @Inject
    VolumeService volumeService;

    private final List<MockWorker> workers = new ArrayList<>();
    private Fixtures.Actor alice;
    private UUID project;
    private UUID template;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        alice = fixtures.createActor("alice");
        project = fixtures.createProject("mine", alice);
        template = fixtures.createTemplate("hello", project, fixtures.createResourceClass("small"));
    }

    @AfterEach
    void disconnect() {
        workers.forEach(MockWorker::close);
        await(() -> workerService.getActiveWorkers().isEmpty(), "a worker session outlived its test", SETTLE);
        for (var worker : workers) {
            var id = worker.workerId();
            // Dropping the session only starts the teardown: the jobs that were running there are
            // failed in transactions of its own, and the next test's TRUNCATE would deadlock against
            // them. Waiting on the roster alone does not.
            await(() -> Fixtures.inTx(() -> Job.listOpenByWorker(id).isEmpty()),
                    "jobs of worker " + id + " were still open", SETTLE);
        }
    }

    // ---------------------------------------------------------------- running a job

    @Test
    void aJobTheControlPlanePlacesOnTheMockRunsToSuccess() {
        start(worker().onJob(JobScript.started()
                .andThen(JobScript.log("the mock is building"))
                .andThen(JobRun::succeeded)));

        var jobId = launch();

        await(() -> stateOf(jobId) == JobState.SUCCESS, "the job never succeeded", SETTLE);
        var logs = logsOf(jobId);
        assertTrue(logs.stream().anyMatch(log -> "stdout".equals(log.getTopic())
                        && "the mock is building".equals(log.getMessage())),
                "the script's log line was never stored: " + logs);
        assertTrue(logs.stream().anyMatch(log -> "state".equals(log.getTopic())
                        && "PENDING -> RUNNING".equals(log.getMessage())),
                "the state transition was never stored: " + logs);
    }

    /** What the worker is handed has to be what the project asked for, secrets included. */
    @Test
    void theJobArrivesAtTheMockWithItsSpecAndItsSecrets() {
        var worker = start(worker().onJob(JobScript.success()));
        fixtures.createSecret(project, "TOKEN", "s3cret");

        var jobId = launch();

        await(() -> stateOf(jobId) == JobState.SUCCESS, "the job never succeeded", SETTLE);
        var job = worker.job(jobId);
        assertEquals("alpine", job.spec().image());
        assertEquals("small", job.resourceClass().name());
        assertEquals("s3cret", job.secrets().get("TOKEN"));
    }

    @Test
    void aJobTheMockFailsIsReportedWithWhatItSaid() {
        start(worker().onJob(JobScript.failure("the build broke")));

        var jobId = launch();

        await(() -> stateOf(jobId) == JobState.FAILED, "the job never failed", SETTLE);
        var logs = logsOf(jobId);
        assertTrue(logs.stream().anyMatch(log -> "stderr".equals(log.getTopic())
                        && Boolean.TRUE.equals(log.getError())
                        && "the build broke".equals(log.getMessage())),
                "the script's error was never stored: " + logs);
    }

    @Test
    void cancellingAJobReachesTheMock() {
        var worker = start(worker().onJob(JobScript.busy()));

        var jobId = launch();
        await(() -> stateOf(jobId) == JobState.RUNNING, "the job never started running", SETTLE);
        // Asserted here, not after a job that ran to SUCCESS: the launcher records the host once the
        // worker acknowledges, and a script reporting a terminal state in those few milliseconds wins
        // the race and leaves the job with no host. This script holds the job open, so it cannot.
        assertEquals(worker.workerId(), workerOf(jobId));

        jobService.cancel(project, jobId);

        assertEquals(JobState.CANCELLED, stateOf(jobId));
        await(() -> worker.job(jobId).wasCancelled(), "the worker was never told to stop", SETTLE);
        assertFalse(worker.job(jobId).wasInterrupted());
    }

    @Test
    void oneMockCanBeGivenTwoJobsAtOnce() {
        var worker = start(worker().onJob(JobScript.success()));

        var first = launch();
        var second = launch();

        await(() -> stateOf(first) == JobState.SUCCESS && stateOf(second) == JobState.SUCCESS,
                "both jobs never succeeded", SETTLE);
        assertEquals(2, worker.jobs().size());
    }

    // ---------------------------------------------------------------- artifacts

    @Test
    void anArtifactTheMockUploadsIsRecorded() {
        // An upload is only promoted while the job is still open and assigned to the worker that
        // sent it, and the sweeper only looks every two seconds: a script that uploads and finishes
        // at once loses the artifact. This one holds the job open until the artifact is visible.
        var placed = new CountDownLatch(1);
        start(worker().onJob(JobScript.started()
                // The request itself is refused while the job has no host yet, and the launcher
                // records the host only after the worker has acknowledged it.
                .andThen(job -> placed.await(30, TimeUnit.SECONDS))
                .andThen(JobScript.upload("report.txt", "hello from the mock"))
                .andThen(JobRun::awaitCancellation)));

        var jobId = launch();
        placed.countDown();

        await(() -> artifactsOf(jobId).size() == 1, "the artifact was never recorded", PROMOTE);
        var artifact = artifactsOf(jobId).get(0);
        assertEquals("report.txt", artifact.getName());
        assertEquals("hello from the mock".getBytes(StandardCharsets.UTF_8).length, artifact.getSizeBytes());

        jobService.cancel(project, jobId);
        assertEquals(1, artifactsOf(jobId).size());
    }

    // ---------------------------------------------------------------- volumes

    @Test
    void aVolumeIsProvisionedOnTheMockAndReleasedOnRequest() {
        var worker = start(worker().onJob(JobScript.success()));

        var volume = volumeService.create(project, "shared", 1024);

        assertEquals(VolumeState.READY, volume.getState());
        assertEquals(worker.workerId(), volume.getWorker().getId());
        assertEquals(volume.getId(), worker.volumes().get(0).volumeId());

        volumeService.delete(project, volume.getId());

        await(() -> worker.deletedVolumes().contains(volume.getId()),
                "the volume was never released on the worker", SETTLE);
    }

    // ---------------------------------------------------------------- harness

    private MockWorker.Builder worker() {
        return MockWorker.builder(baseUri, SECRET).name("mock-worker");
    }

    private MockWorker start(MockWorker.Builder builder) {
        // Tracked before it registers so a refused registration still leaves its socket to be closed.
        var worker = builder.connect();
        workers.add(worker);
        return worker.register();
    }

    /** Launches a job against the fixture template, asserting that placement found the mock. */
    private UUID launch() {
        var created = launcher.launch(
                project, alice.id(), new JobRequest(template, null, null, null), JobLauncher.PRE_AUTHORIZED);
        assertTrue(created.scheduled(), "the job was never placed on the mock");
        return created.job().getId();
    }

    private static JobState stateOf(UUID jobId) {
        return Fixtures.inTx(() -> Job.<Job>findById(jobId).getState());
    }

    private static UUID workerOf(UUID jobId) {
        return Fixtures.inTx(() -> Job.<Job>findById(jobId).getWorker());
    }

    private static List<JobLog> logsOf(UUID jobId) {
        return Fixtures.inTx(() -> JobLog.listByJob(jobId, 0, 100));
    }

    private static List<Artifact> artifactsOf(UUID jobId) {
        return Fixtures.inTx(() -> Artifact.listByJob(jobId));
    }

    private static void await(BooleanSupplier settled, String message, Duration limit) {
        var deadline = System.nanoTime() + limit.toNanos();
        while (!settled.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(message + ", after " + limit);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(message);
            }
        }
    }
}
