package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.entity.VolumeState;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests the administrative surface under {@code /api/admin}.
 */
@QuarkusTest
@Tag("e2e")
class AdminResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private Fixtures.Actor admin;
    private Fixtures.Actor alice;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        alice = fixtures.createActor("alice");
    }

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/admin/stats").then().statusCode(401);
    }

    @Test
    void anOrdinaryUserReachesNothingUnderAdmin() {
        as(alice).get("/api/admin/stats").then().statusCode(403);
        as(alice).get("/api/admin/project").then().statusCode(403);
        as(alice).get("/api/admin/user").then().statusCode(403);
        as(alice).get("/api/admin/template").then().statusCode(403);
        as(alice).get("/api/admin/resource-class").then().statusCode(403);
        as(alice).get("/api/admin/permission").then().statusCode(403);
        as(alice).get("/api/admin/task").then().statusCode(403);
        as(alice).get("/api/admin/volume").then().statusCode(403);
        as(alice).get("/api/admin/artifact").then().statusCode(403);
    }

    @Test
    void theStatsCountEverything() {
        var project = fixtures.createProject("mine", alice);
        var archived = fixtures.createProject("old", alice);
        fixtures.archive(archived);
        var small = fixtures.createResourceClass("small");
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createArtifact(job, "out.txt");
        var worker = fixtures.createWorker("w1");
        fixtures.createVolume(project, worker, "data");
        fixtures.createTask(project, alice, "release", null);

        as(admin).get("/api/admin/stats").then()
                .statusCode(200)
                .body("users.total", equalTo(2))
                .body("users.subAccounts", equalTo(0))
                .body("projects.total", equalTo(2))
                .body("projects.archived", equalTo(1))
                .body("workers.registered", equalTo(1))
                .body("workers.connected", equalTo(0))
                .body("jobs.byState.SUCCESS", equalTo(1))
                .body("jobs.completedLast24h", equalTo(1))
                .body("tasks.byState.OPEN", equalTo(1))
                .body("storage.artifacts", equalTo(1))
                .body("storage.bytes", equalTo(1))
                .body("storage.volumes", equalTo(1))
                .body("storage.volumeBytes", equalTo(1024))
                .body("system.startedAt", notNullValue())
                .body("system.version", notNullValue())
                .body("system.environment", notNullValue());
    }

    /**
     * A state the query returned nothing for is still published, so a client never has to tell an
     * absent key from a count of none.
     */
    @Test
    void everyGroupingIsZeroFilled() {
        as(admin).get("/api/admin/stats").then()
                .statusCode(200)
                .body("jobs.byState.keySet()", containsInAnyOrder(
                        "PENDING", "RUNNING", "FAILED", "SUCCESS", "CANCELLED"))
                .body("jobs.byState.PENDING", equalTo(0))
                .body("queue.byState.QUEUED", equalTo(0))
                .body("tasks.byState.CLOSED", equalTo(0));
    }

    /** The series is the axis a failure-rate chart is drawn on, so it is fixed-length and hour-aligned. */
    @Test
    void theCompletionSeriesIsAFullDayOfBuckets() {
        var project = fixtures.createProject("mine", alice);
        var small = fixtures.createResourceClass("small");
        fixtures.createJob(project, alice, small, JobState.FAILED, UUID.randomUUID());

        as(admin).get("/api/admin/stats").then()
                .statusCode(200)
                .body("jobs.hourlyLast24h", hasSize(24))
                // A job completed just now falls in the last bucket, which is the current hour.
                .body("jobs.hourlyLast24h[23].failed", equalTo(1))
                .body("jobs.hourlyLast24h[0].failed", equalTo(0))
                .body("jobs.completedLast24h", equalTo(1));
    }

    @Test
    void theProjectListingCountsMembersJobsAndQueue() {
        var project = fixtures.createProject("mine", alice);
        var small = fixtures.createResourceClass("small");
        var template = fixtures.createTemplate("build", project, small);
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createQueuedJob(project, alice, template, "small");

        as(admin).get("/api/admin/project").then()
                .statusCode(200)
                .body("name", contains("mine"))
                .body("[0].members", equalTo(1))
                .body("[0].jobs", equalTo(1))
                .body("[0].queued", equalTo(1))
                .body("[0].archivedAt", nullValue());
    }

    @Test
    void theProjectListingFiltersByName() {
        fixtures.createProject("alpha");
        fixtures.createProject("beta");

        as(admin).queryParam("query", "ALP").get("/api/admin/project").then()
                .statusCode(200)
                .body("name", contains("alpha"));
    }

    @Test
    void theUserListingFindsPeopleByNameOrEmail() {
        as(admin).queryParam("query", "alice").get("/api/admin/user").then()
                .statusCode(200)
                .body("name", contains("alice"))
                .body("[0].subAccountOf", nullValue());
    }

    @Test
    void aSubAccountIsListedWithItsProject() {
        var project = fixtures.createProject("mine", alice);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(admin).queryParam("query", "ci").get("/api/admin/user/{u}", ci.id()).then()
                .statusCode(200)
                .body("user.subAccountOf", equalTo(project.toString()));
    }

    @Test
    void aUserDetailShowsMembershipsAndGrants() {
        var project = fixtures.createProject("mine");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.grant(alice, Perm.JOB_CANCEL, project);

        as(admin).get("/api/admin/user/{u}", alice.id()).then()
                .statusCode(200)
                .body("user.name", equalTo("alice"))
                .body("memberships.projectName", contains("mine"))
                .body("memberships[0].role", equalTo("MEMBER"))
                .body("globalPermissions", empty())
                .body("projectPermissions.'" + project + "'", contains(Perm.JOB_CANCEL.permission()));
    }

    @Test
    void aUserThatDoesNotExistIsNotFound() {
        var absent = UUID.randomUUID();

        as(admin).get("/api/admin/user/{u}", absent).then()
                .statusCode(404)
                .body("message", equalTo("no such user: " + absent));
    }

    @Test
    void anAdminGrantsAndRevokesAGlobalPermission() {
        as(admin).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of(Perm.PROJECT_CREATE.permission())))
                .put("/api/admin/user/{u}/permission/global", alice.id()).then()
                .statusCode(200)
                .body("globalPermissions", contains(Perm.PROJECT_CREATE.permission()));

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "fresh"))
                .post("/api/project").then().statusCode(201);

        as(admin).contentType(ContentType.JSON).body(Map.of("permissions", List.of()))
                .put("/api/admin/user/{u}/permission/global", alice.id()).then()
                .statusCode(200)
                .body("globalPermissions", empty());

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "fresh2"))
                .post("/api/project").then().statusCode(403);
    }

    @Test
    void aProjectScopedPermissionIsNotGrantableGlobally() {
        as(admin).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of(Perm.JOB_CREATE.permission())))
                .put("/api/admin/user/{u}/permission/global", alice.id()).then()
                .statusCode(400)
                .body("message", equalTo("not a global permission: " + Perm.JOB_CREATE.permission()));
    }

    @Test
    void anUnknownPermissionIsRejected() {
        as(admin).contentType(ContentType.JSON).body(Map.of("permissions", List.of("job:teleport")))
                .put("/api/admin/user/{u}/permission/global", alice.id()).then()
                .statusCode(400)
                .body("message", equalTo("unknown permission: job:teleport"));
    }

    /**
     * permission.project_id is a bare UUID with no foreign key, so an unchecked scope would write an
     * orphan grant — and the all-zero UUID would come back as a global one.
     */
    @Test
    void aGrantCannotNameAProjectThatDoesNotExist() {
        as(admin).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of(Perm.PROJECT_READ.permission())))
                .put("/api/admin/user/{u}/permission/project/{p}", alice.id(), UUID.randomUUID()).then()
                .statusCode(404);

        as(admin).get("/api/admin/user/{u}", alice.id()).then()
                .statusCode(200)
                .body("globalPermissions", hasSize(0))
                .body("projectPermissions", anEmptyMap());
    }

    @Test
    void anAdminSetsSomeonesProjectPermissions() {
        var project = fixtures.createProject("mine");

        as(admin).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of(
                        Perm.PROJECT_READ.permission(), Perm.JOB_READ.permission())))
                .put("/api/admin/user/{u}/permission/project/{p}", alice.id(), project).then()
                .statusCode(200)
                .body("projectPermissions.'" + project + "'",
                        containsInAnyOrder(Perm.PROJECT_READ.permission(), Perm.JOB_READ.permission()));

        as(alice).get("/api/project/{p}", project).then()
                .statusCode(200)
                .body("access", equalTo("PERMISSION"));
    }

    @Test
    void revokingEverythingLeavesNoGrant() {
        var project = fixtures.createProject("mine");
        fixtures.grant(alice, Perm.PROJECT_READ, project);

        as(admin).delete("/api/admin/user/{u}/permission", alice.id()).then()
                .statusCode(200)
                .body("globalPermissions", empty())
                .body("projectPermissions", equalTo(Map.of()));

        as(alice).get("/api/project/{p}", project).then().statusCode(403);
    }

    /** The last admin:all holder cannot revoke their own admin permission. */
    @Test
    void theLastAdminCannotGiveUpAdminOfAll() {
        as(admin).contentType(ContentType.JSON).body(Map.of("permissions", List.of()))
                .put("/api/admin/user/{u}/permission/global", admin.id()).then()
                .statusCode(409)
                .body("message", equalTo(
                        "the last " + Perm.ADMIN_OF_ALL.permission() + " holder cannot give it up"));

        as(admin).delete("/api/admin/user/{u}/permission", admin.id()).then().statusCode(409);
    }

    @Test
    void anAdminCanStepDownOnceThereIsAnother() {
        fixtures.makeAdmin(alice);

        as(admin).contentType(ContentType.JSON).body(Map.of("permissions", List.of()))
                .put("/api/admin/user/{u}/permission/global", admin.id()).then().statusCode(200);

        as(admin).get("/api/admin/stats").then().statusCode(403);
        as(alice).get("/api/admin/stats").then().statusCode(200);
    }

    @Test
    void anAdminDefinesAndDropsAGlobalTemplate() {
        fixtures.createResourceClass("small");

        var created = as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "shared", "resourceClass", "small", "spec", Map.of("image", "alpine")))
                .post("/api/admin/template").then()
                .statusCode(201)
                .body("name", equalTo("shared"))
                .body("projectId", nullValue())
                .extract().path("id");

        as(admin).get("/api/admin/template").then()
                .statusCode(200)
                .body("name", contains("shared"));

        // Global templates are visible to projects.
        var project = fixtures.createProject("mine", alice);
        as(alice).get("/api/project/{p}/job/template", project).then()
                .statusCode(200)
                .body("name", hasItem("shared"));

        as(admin).delete("/api/admin/template/{t}", created).then().statusCode(204);
        as(admin).get("/api/admin/template").then().body("$", empty());
    }

    @Test
    void aGlobalTemplateNeedsAnExistingResourceClass() {
        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "shared", "resourceClass", "small", "spec", Map.of("image", "alpine")))
                .post("/api/admin/template").then()
                .statusCode(404)
                .body("message", equalTo("no such resource class: small"));
    }

    @Test
    void aGlobalTemplateCannotMountVolumes() {
        fixtures.createResourceClass("small");
        var project = fixtures.createProject("mine");
        var volume = fixtures.createVolume(project, fixtures.createWorker("w1"), "data");

        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "shared",
                        "resourceClass", "small",
                        "spec", Map.of("image", "alpine",
                                "volumes", Map.of(volume.toString(), Map.of("mountPoint", "/data")))))
                .post("/api/admin/template").then()
                .statusCode(400)
                .body("message", equalTo("a global template cannot mount volumes"));
    }

    /** Global template endpoints do not manage project-scoped templates. */
    @Test
    void aProjectTemplateIsNotVisibleToTheGlobalListing() {
        var project = fixtures.createProject("mine");
        var small = fixtures.createResourceClass("small");
        var theirs = fixtures.createTemplate("build", project, small);

        as(admin).get("/api/admin/template").then().body("$", empty());
        as(admin).delete("/api/admin/template/{t}", theirs).then().statusCode(404);
    }

    @Test
    void anAdminDefinesUpdatesAndDropsAResourceClass() {
        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "memory-small", "numCpus", 2, "memCount", 512, "diskSize", 1024))
                .post("/api/admin/resource-class").then()
                .statusCode(201)
                .body("name", equalTo("memory-small"))
                .body("numCpus", equalTo(2));

        as(admin).get("/api/admin/resource-class").then()
                .statusCode(200)
                .body("name", contains("memory-small"));

        as(admin).contentType(ContentType.JSON).body(Map.of("numCpus", 8))
                .patch("/api/admin/resource-class/memory-small").then()
                .statusCode(200)
                .body("numCpus", equalTo(8))
                .body("memCount", equalTo(512));

        as(admin).delete("/api/admin/resource-class/memory-small").then().statusCode(204);
        as(admin).get("/api/admin/resource-class").then().body("$", empty());
    }

    /** A class has to exist before anything can name one, so this is how an install bootstraps. */
    @Test
    void aClassDefinedHereUnblocksTemplatesAndThenResistsDeletion() {
        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "small", "numCpus", 1, "memCount", 512, "diskSize", 1024))
                .post("/api/admin/resource-class").then().statusCode(201);

        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "shared", "resourceClass", "small", "spec", Map.of("image", "alpine")))
                .post("/api/admin/template").then().statusCode(201);

        as(admin).delete("/api/admin/resource-class/small").then()
                .statusCode(409)
                .body("message", equalTo(
                        "resource class small is still named by 0 job(s) and 1 template(s)"));
    }

    @Test
    void aResourceClassNameIsTakenOnlyOnce() {
        fixtures.createResourceClass("small");

        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "small", "numCpus", 1, "memCount", 1, "diskSize", 1))
                .post("/api/admin/resource-class").then()
                .statusCode(409)
                .body("message", equalTo("a resource class named small already exists"));
    }

    @Test
    void aResourceClassRejectsNegativeCapacityAndBadNames() {
        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "small", "numCpus", -1, "memCount", 1, "diskSize", 1))
                .post("/api/admin/resource-class").then()
                .statusCode(400)
                .body("message", equalTo("numCpus must not be negative"));

        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "-nope", "numCpus", 1, "memCount", 1, "diskSize", 1))
                .post("/api/admin/resource-class").then()
                .statusCode(400)
                .body("message", equalTo("name must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}"));

        fixtures.createResourceClass("small");
        as(admin).contentType(ContentType.JSON).body(Map.of())
                .patch("/api/admin/resource-class/small").then()
                .statusCode(400)
                .body("message", equalTo("numCpus, memCount or diskSize is required"));
    }

    /** One request for the whole cluster's tasks; the per-project listing would be one per project. */
    @Test
    void theTaskListingSpansProjectsAndNamesThem() {
        var aurora = fixtures.createProject("aurora", alice);
        var nebula = fixtures.createProject("nebula", alice);
        fixtures.createTask(aurora, alice, "release", null);
        fixtures.createTask(nebula, alice, "flaky login test", null);

        as(admin).get("/api/admin/task").then()
                .statusCode(200)
                .body("name", containsInAnyOrder("release", "flaky login test"))
                .body("projectName", containsInAnyOrder("aurora", "nebula"));

        as(admin).queryParam("query", "FLAKY").get("/api/admin/task").then()
                .statusCode(200)
                .body("name", contains("flaky login test"));
    }

    @Test
    void theTaskListingNarrowsByState() {
        var project = fixtures.createProject("mine", alice);
        fixtures.createTask(project, alice, "open one", null);
        var closing = fixtures.createTask(project, alice, "closed one", null);
        as(alice).delete("/api/project/{p}/task/{t}", project, closing).then().statusCode(200);

        as(admin).queryParam("state", "OPEN").get("/api/admin/task").then()
                .statusCode(200)
                .body("name", contains("open one"));
        as(admin).queryParam("state", "CLOSED").get("/api/admin/task").then()
                .statusCode(200)
                .body("name", contains("closed one"));
    }

    @Test
    void theVolumeListingSpansWorkersAndNarrowsByEachFilter() {
        var aurora = fixtures.createProject("aurora", alice);
        var nebula = fixtures.createProject("nebula", alice);
        var one = fixtures.createWorker("w1");
        var two = fixtures.createWorker("w2");
        fixtures.createVolume(aurora, one, "gradle-cache");
        fixtures.createVolume(nebula, two, "release-staging", VolumeState.PROVISIONING);

        as(admin).get("/api/admin/volume").then()
                .statusCode(200)
                .body("name", containsInAnyOrder("gradle-cache", "release-staging"))
                .body("projectName", containsInAnyOrder("aurora", "nebula"));

        as(admin).queryParam("worker", one).get("/api/admin/volume").then()
                .body("name", contains("gradle-cache"));
        as(admin).queryParam("project", nebula).get("/api/admin/volume").then()
                .body("name", contains("release-staging"));
        as(admin).queryParam("state", "READY").get("/api/admin/volume").then()
                .body("name", contains("gradle-cache"));
    }

    /** Artifacts are otherwise reachable only inside a JobView the client already fetched. */
    @Test
    void theArtifactListingSpansProjectsAndNarrowsByProjectOrJob() {
        var aurora = fixtures.createProject("aurora", alice);
        var nebula = fixtures.createProject("nebula", alice);
        var small = fixtures.createResourceClass("small");
        var build = fixtures.createJob(aurora, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var test = fixtures.createJob(nebula, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createArtifact(build, "build.log");
        fixtures.createArtifact(test, "junit-report.xml");

        as(admin).get("/api/admin/artifact").then()
                .statusCode(200)
                .body("name", containsInAnyOrder("build.log", "junit-report.xml"))
                .body("projectName", containsInAnyOrder("aurora", "nebula"))
                .body("[0].createdAt", notNullValue())
                .body("[0].size", equalTo(1));

        as(admin).queryParam("project", aurora).get("/api/admin/artifact").then()
                .body("name", contains("build.log"))
                .body("[0].jobId", equalTo(build.toString()));
        as(admin).queryParam("job", test).get("/api/admin/artifact").then()
                .body("name", contains("junit-report.xml"));
    }

    /**
     * The point of the update is that the ID survives it: a queued job and a re-run payload both hold
     * one, and delete-and-recreate would leave them pointing at a template that no longer exists.
     */
    @Test
    void aGlobalTemplateIsUpdatedInPlace() {
        fixtures.createResourceClass("small");
        fixtures.createResourceClass("large");
        var created = as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "shared", "resourceClass", "small",
                        "spec", Map.of("image", "alpine", "environment", Map.of("A", "1"))))
                .post("/api/admin/template").then().statusCode(201)
                .extract().path("id").toString();

        as(admin).contentType(ContentType.JSON).body(Map.of("name", "renamed"))
                .patch("/api/admin/template/{t}", created).then()
                .statusCode(200)
                .body("id", equalTo(created))
                .body("name", equalTo("renamed"))
                .body("resourceClass", equalTo("small"))
                .body("spec.image", equalTo("alpine"))
                .body("spec.environment.A", equalTo("1"));

        // A supplied spec replaces the stored one, which is how an entry gets removed.
        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "resourceClass", "large", "spec", Map.of("image", "debian", "timeout", 30)))
                .patch("/api/admin/template/{t}", created).then()
                .statusCode(200)
                .body("id", equalTo(created))
                .body("name", equalTo("renamed"))
                .body("resourceClass", equalTo("large"))
                .body("spec.image", equalTo("debian"))
                .body("spec.timeout", equalTo(30))
                .body("spec.environment", anEmptyMap());
    }

    @Test
    void aTemplateUpdateRejectsAnEmptyBodyAnUnknownClassAndVolumes() {
        fixtures.createResourceClass("small");
        var project = fixtures.createProject("mine", alice);
        var volume = fixtures.createVolume(project, fixtures.createWorker("w1"), "data");
        var created = as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "shared", "resourceClass", "small", "spec", Map.of("image", "alpine")))
                .post("/api/admin/template").then().statusCode(201)
                .extract().path("id").toString();

        as(admin).contentType(ContentType.JSON).body(Map.of())
                .patch("/api/admin/template/{t}", created).then()
                .statusCode(400)
                .body("message", equalTo("name, resourceClass or spec is required"));

        as(admin).contentType(ContentType.JSON).body(Map.of("resourceClass", "nope"))
                .patch("/api/admin/template/{t}", created).then()
                .statusCode(404)
                .body("message", equalTo("no such resource class: nope"));

        as(admin).contentType(ContentType.JSON).body(Map.of("spec", Map.of(
                        "image", "alpine",
                        "volumes", Map.of(volume.toString(), Map.of("mountPoint", "/data")))))
                .patch("/api/admin/template/{t}", created).then()
                .statusCode(400)
                .body("message", equalTo("a global template cannot mount volumes"));
    }

    /** Project templates stay invisible here, for the update as much as for the delete. */
    @Test
    void aProjectTemplateCannotBeUpdatedGlobally() {
        var project = fixtures.createProject("mine");
        var small = fixtures.createResourceClass("small");
        var theirs = fixtures.createTemplate("build", project, small);

        as(admin).contentType(ContentType.JSON).body(Map.of("name", "hijacked"))
                .patch("/api/admin/template/{t}", theirs).then().statusCode(404);
    }

    /** Lists all available permissions when no permissions are banned. */
    @Test
    void thePermissionCatalogueListsEveryPermission() {
        as(admin).get("/api/admin/permission").then()
                .statusCode(200)
                .body("$", hasSize(Perm.values().length))
                .body("permission", hasItem(Perm.JOB_CREATE.permission()))
                .body("findAll { it.permission == '" + Perm.ADMIN_OF_ALL.permission() + "' }.scope",
                        contains("global"))
                .body("findAll { it.banned == true }", hasSize(0));
    }
}
