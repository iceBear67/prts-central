package io.ib67.prts.project;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.entity.JobLock;
import io.ib67.prts.agent.job.entity.JobSpecTemplate;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.agent.worker.entity.WorkerVolume;
import io.ib67.prts.pending.PendingJob;
import io.ib67.prts.pending.PendingJobState;
import io.ib67.prts.project.entity.Artifact;
import io.ib67.prts.project.entity.Job;
import io.ib67.prts.project.entity.JobLog;
import io.ib67.prts.project.entity.JobState;
import io.ib67.prts.project.entity.Project;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.secret.ProjectSecret;
import io.ib67.prts.secret.user.UserAccessToken;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.ib67.prts.user.Permission;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserToProject;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Supplier;

import static io.ib67.prts.testing.Fixtures.as;
import static io.ib67.prts.testing.Fixtures.inTx;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies cascading deletion in {@link ProjectService#delete}.
 */
@QuarkusTest
@Tag("e2e")
class ProjectDeletionE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;
    @Inject
    ProjectService projectService;

    private UUID mine;
    private UUID theirs;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        mine = fixtures.createProject("mine");
        theirs = fixtures.createProject("theirs");
    }

    @Test
    void everyRowThatHangsOffTheProjectGoes() {
        fill(mine);

        assertTrue(projectService.delete(mine));

        assertNothingLeftOf(mine);
    }

    /** Sub-account user records are deleted along with the project. */
    @Test
    void aSubAccountIsTakenDownWithTheProject() {
        var filled = fill(mine);

        projectService.delete(mine);

        assertAll(
                () -> assertEquals(0, rows(() -> User.count("id", filled.subAccount().id())), "user"),
                () -> assertEquals(0,
                        rows(() -> UserAccessToken.count("userId", filled.subAccount().id())), "token"));
        as(filled.subAccount()).get("/api/project").then().statusCode(401);
    }

    /** User accounts of project members are preserved when the project is deleted. */
    @Test
    void aMemberOutlivesTheProjectTheyWereIn() {
        var filled = fill(mine);

        projectService.delete(mine);

        assertAll(
                () -> assertEquals(1, rows(() -> User.count("id", filled.owner().id())), "user"),
                () -> assertEquals(1,
                        rows(() -> UserAccessToken.count("userId", filled.owner().id())), "token"));
        as(filled.owner()).get("/api/project").then().statusCode(200).body("$", empty());
    }

    @Test
    void anotherProjectKeepsEverythingOfItsOwn() {
        fill(mine);
        fill(theirs);

        projectService.delete(mine);

        assertAll(
                () -> assertEquals(1, rows(() -> Project.count("id", theirs)), "project"),
                () -> assertEquals(2, rows(() -> Job.count("project.id", theirs)), "job"),
                () -> assertEquals(1, rows(() -> JobLog.count("job.project.id", theirs)), "job_log"),
                () -> assertEquals(1, rows(() -> Artifact.count("job.project.id", theirs)), "artifact"),
                () -> assertEquals(1, rows(() -> JobLock.count("id.projectId", theirs)), "job_lock"),
                () -> assertEquals(1, rows(() -> PendingJob.count("project.id", theirs)), "pending_job"),
                () -> assertEquals(1,
                        rows(() -> JobSpecTemplate.count("project.id", theirs)), "job_spec_template"),
                () -> assertEquals(1, rows(() -> ResourceClass.count("projectId", theirs)), "resource_class"),
                () -> assertEquals(1, rows(() -> WorkerVolume.count("project.id", theirs)), "worker_volume"),
                () -> assertEquals(1, rows(() -> ProjectSecret.count("id.projectId", theirs)), "project_secret"),
                () -> assertEquals(1, rows(() -> SubAccount.count("project.id", theirs)), "sub_account"),
                () -> assertEquals(1, rows(() -> UserToProject.count("id.projectId", theirs)), "user_to_project"),
                () -> assertEquals(1,
                        rows(() -> Permission.count("id.projectId", theirs)), "user_permission"));
    }

    /** Deleting a project does not affect pending job queues of other projects. */
    @Test
    void anotherProjectsQueueStaysInLine() {
        var alice = fixtures.createActor("alice");
        var template = fixtures.createTemplate("build", null, fixtures.createResourceClass("small", null));
        var queued = fixtures.createQueuedJob(theirs, alice, template, "small");
        fixtures.createQueuedJob(mine, alice, template, "small");

        projectService.delete(mine);

        assertEquals(PendingJobState.QUEUED,
                inTx(() -> PendingJob.<PendingJob>findById(queued).getState()));
    }

    /** Global resource classes and templates are preserved when a project is deleted. */
    @Test
    void theGlobalDefinitionsAreNotTheProjectsToTake() {
        var global = fixtures.createResourceClass("shared", null);
        fixtures.createTemplate("shared", null, global);
        fill(mine);

        projectService.delete(mine);

        assertAll(
                () -> assertEquals(1,
                        rows(() -> ResourceClass.count("projectId", ResourceClass.GLOBAL)), "resource_class"),
                () -> assertEquals(1,
                        rows(() -> JobSpecTemplate.count("project is null")), "job_spec_template"));
    }

    /** Global permissions are preserved when a project is deleted. */
    @Test
    void aGlobalGrantIsNotAProjectGrant() {
        var admin = fixtures.createActor("admin");
        fixtures.makeAdmin(admin);
        fill(mine);

        projectService.delete(mine);

        assertEquals(1, rows(() -> Permission.count("id.userId", admin.id())));
    }

    @Test
    void deletingAProjectThatIsNotThereReportsNothingDeleted() {
        assertFalse(projectService.delete(UUID.randomUUID()));
    }

    @Test
    void deletingTwiceReportsNothingTheSecondTime() {
        fill(mine);

        assertTrue(projectService.delete(mine));
        assertFalse(projectService.delete(mine));
    }

    private void assertNothingLeftOf(UUID projectId) {
        assertAll(
                () -> assertEquals(0, rows(() -> Project.count("id", projectId)), "project"),
                () -> assertEquals(0, rows(() -> Job.count("project.id", projectId)), "job"),
                () -> assertEquals(0, rows(() -> JobLog.count("job.project.id", projectId)), "job_log"),
                () -> assertEquals(0, rows(() -> Artifact.count("job.project.id", projectId)), "artifact"),
                () -> assertEquals(0, rows(() -> JobLock.count("id.projectId", projectId)), "job_lock"),
                () -> assertEquals(0, rows(() -> PendingJob.count("project.id", projectId)), "pending_job"),
                () -> assertEquals(0,
                        rows(() -> JobSpecTemplate.count("project.id", projectId)), "job_spec_template"),
                () -> assertEquals(0,
                        rows(() -> ResourceClass.count("projectId", projectId)), "resource_class"),
                () -> assertEquals(0,
                        rows(() -> WorkerVolume.count("project.id", projectId)), "worker_volume"),
                () -> assertEquals(0,
                        rows(() -> ProjectSecret.count("id.projectId", projectId)), "project_secret"),
                () -> assertEquals(0, rows(() -> SubAccount.count("project.id", projectId)), "sub_account"),
                () -> assertEquals(0,
                        rows(() -> UserToProject.count("id.projectId", projectId)), "user_to_project"),
                () -> assertEquals(0,
                        rows(() -> Permission.count("id.projectId", projectId)), "user_permission"));
    }

    // Populates sample data across all project-related tables.
    private Filled fill(UUID projectId) {
        var owner = fixtures.createActor("owner");
        fixtures.join(owner, projectId, ProjectRole.OWNER);
        fixtures.grant(owner, Perm.JOB_CREATE, projectId);
        var subAccount = fixtures.createSubAccount(projectId, "ci", owner);
        fixtures.createSecret(projectId, "TOKEN", "s3cret");
        var klass = fixtures.createResourceClass("small", projectId);
        var template = fixtures.createTemplate("run", projectId, klass);
        var done = fixtures.createJob(projectId, owner, klass, JobState.SUCCESS, null);
        fixtures.createLog(done, "state", "RUNNING -> SUCCESS");
        fixtures.createArtifact(done, "out.tar");
        // Acquire lock with completed job to test cascading deletion of JobLock.
        inTx(() -> JobLock.tryAcquire("deploy", done));
        fixtures.createJob(projectId, owner, klass, JobState.RUNNING, fixtures.createWorker("w-" + projectId));
        fixtures.createVolume(projectId, fixtures.createWorker("v-" + projectId), "cache");
        fixtures.createQueuedJob(projectId, owner, template, "small");
        return new Filled(owner, subAccount);
    }

    private static long rows(Supplier<Long> count) {
        return inTx(count);
    }

    private record Filled(Fixtures.Actor owner, Fixtures.Actor subAccount) {
    }
}
