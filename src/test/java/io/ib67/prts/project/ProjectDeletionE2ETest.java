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
 * What {@link ProjectService#delete} tears down, and what it must leave standing.
 *
 * <p>Most of the teardown is a foreign key doing its job, which is exactly why it is worth asserting:
 * a table added later inherits no cascade, and the only place that shows is a row nobody can reach.
 *
 * <p>Not covered: the {@code BUSY} round trip and the 409 beyond it. Reaching it takes a job placed
 * between {@code stopWork} and {@code deleteRows}, and the pessimistic lock the latter takes means the
 * race cannot be staged from a single thread.
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
        mine = fixtures.project("mine");
        theirs = fixtures.project("theirs");
    }

    @Test
    void everyRowThatHangsOffTheProjectGoes() {
        fill(mine);

        assertTrue(projectService.delete(mine));

        assertNothingLeftOf(mine);
    }

    /**
     * A sub-account exists only to act on one project, so its {@code prts_user} row is the project's to
     * take — the cascade would otherwise leave a login behind with nothing to log in to.
     */
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

    /** A member is a person, not project property: only the membership row was the project's. */
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

    /** Its queue is cancelled by project id; another project's entries are still waiting their turn. */
    @Test
    void anotherProjectsQueueStaysInLine() {
        var alice = fixtures.actor("alice");
        var template = fixtures.template("build", null, fixtures.resourceClass("small", null));
        var queued = fixtures.queued(theirs, alice, template, "small");
        fixtures.queued(mine, alice, template, "small");

        projectService.delete(mine);

        assertEquals(PendingJobState.QUEUED,
                inTx(() -> PendingJob.<PendingJob>findById(queued).getState()));
    }

    /** Global definitions back every project, so one project's exit must not take them. */
    @Test
    void theGlobalDefinitionsAreNotTheProjectsToTake() {
        var global = fixtures.resourceClass("shared", null);
        fixtures.template("shared", null, global);
        fill(mine);

        projectService.delete(mine);

        assertAll(
                () -> assertEquals(1,
                        rows(() -> ResourceClass.count("projectId", ResourceClass.GLOBAL)), "resource_class"),
                () -> assertEquals(1,
                        rows(() -> JobSpecTemplate.count("project is null")), "job_spec_template"));
    }

    /** Grants are wiped by project id, and a global grant is keyed on the sentinel rather than one. */
    @Test
    void aGlobalGrantIsNotAProjectGrant() {
        var admin = fixtures.actor("admin");
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

    /** One row of every kind a project can own, so that anything the delete misses shows up. */
    private Filled fill(UUID projectId) {
        var owner = fixtures.actor("owner");
        fixtures.join(owner, projectId, ProjectRole.OWNER);
        fixtures.grant(owner, Perm.JOB_CREATE, projectId);
        var subAccount = fixtures.subAccount(projectId, "ci", owner);
        fixtures.secret(projectId, "TOKEN", "s3cret");
        var klass = fixtures.resourceClass("small", projectId);
        var template = fixtures.template("run", projectId, klass);
        var done = fixtures.job(projectId, owner, klass, JobState.SUCCESS, null);
        fixtures.log(done, "state", "RUNNING -> SUCCESS");
        fixtures.artifact(done, "out.tar");
        // Held by a finished job on purpose: a live one would be cancelled on the way out, and the
        // release that follows would hide whether the cascade reaches job_lock at all.
        inTx(() -> JobLock.tryAcquire("deploy", done));
        fixtures.job(projectId, owner, klass, JobState.RUNNING, fixtures.worker("w-" + projectId));
        fixtures.volume(projectId, fixtures.worker("v-" + projectId), "cache");
        fixtures.queued(projectId, owner, template, "small");
        return new Filled(owner, subAccount);
    }

    private static long rows(Supplier<Long> count) {
        return inTx(count);
    }

    private record Filled(Fixtures.Actor owner, Fixtures.Actor subAccount) {
    }
}
