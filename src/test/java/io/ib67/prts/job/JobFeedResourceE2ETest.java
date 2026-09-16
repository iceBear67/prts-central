package io.ib67.prts.job;

import io.ib67.prts.Perm;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * The cross-project feed: what the caller can read, everywhere, in one request.
 */
@QuarkusTest
@Tag("e2e")
class JobFeedResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private Fixtures.Actor alice;
    private UUID aurora;
    private UUID nebula;
    private UUID theirs;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        alice = fixtures.createActor("alice");
        aurora = fixtures.createProject("aurora", alice);
        nebula = fixtures.createProject("nebula");
        theirs = fixtures.createProject("theirs");
    }

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/job").then().statusCode(401);
    }

    /** Membership and an explicit grant each reach a project on their own. */
    @Test
    void theFeedSpansEveryProjectTheCallerCanRead() {
        var small = fixtures.createResourceClass("small");
        fixtures.grant(alice, Perm.JOB_READ, nebula);
        fixtures.createJob(aurora, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createJob(nebula, alice, small, JobState.FAILED, UUID.randomUUID());
        fixtures.createJob(theirs, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/job").then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items.projectId", containsInAnyOrder(aurora.toString(), nebula.toString()))
                .body("total", equalTo(2));
    }

    @Test
    void aCallerWhoReachesNoProjectGetsAnEmptyFeed() {
        var small = fixtures.createResourceClass("small");
        fixtures.createJob(theirs, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(fixtures.createActor("mallory")).get("/api/job").then()
                .statusCode(200)
                .body("items", empty())
                .body("total", equalTo(0));
    }

    @Test
    void anAdminSeesEveryProject() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var small = fixtures.createResourceClass("small");
        fixtures.createJob(aurora, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createJob(theirs, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(admin).get("/api/job").then()
                .statusCode(200)
                .body("total", equalTo(2));
    }

    @Test
    void theFeedNarrowsByProjectStateAndWorker() {
        var small = fixtures.createResourceClass("small");
        var builder = fixtures.createWorker("builder-03");
        fixtures.join(alice, nebula, ProjectRole.VIEWER);
        fixtures.createJob(aurora, alice, small, JobState.SUCCESS, builder);
        fixtures.createJob(nebula, alice, small, JobState.FAILED, UUID.randomUUID());

        as(alice).queryParam("project", aurora).get("/api/job").then()
                .body("items.projectId", contains(aurora.toString()));
        as(alice).queryParam("state", "FAILED").get("/api/job").then()
                .body("items.projectId", contains(nebula.toString()));
        as(alice).queryParam("worker", builder).get("/api/job").then()
                .body("items.worker.name", contains("builder-03"));
    }

    /** A project filter naming somewhere the caller cannot read answers empty, not the rows. */
    @Test
    void aProjectFilterCannotReachPastTheGate() {
        var small = fixtures.createResourceClass("small");
        fixtures.createJob(theirs, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).queryParam("project", theirs).get("/api/job").then()
                .statusCode(200)
                .body("items", empty());
    }

    /** Unplaced PENDING rows are hidden here for the same reason as in the per-project listing. */
    @Test
    void anUnplacedJobIsNotInTheFeed() {
        var small = fixtures.createResourceClass("small");
        fixtures.createJob(aurora, alice, small, JobState.PENDING, null);

        as(alice).get("/api/job").then()
                .statusCode(200)
                .body("items", empty());
    }
}
