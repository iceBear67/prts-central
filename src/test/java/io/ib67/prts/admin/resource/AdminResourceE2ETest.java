package io.ib67.prts.admin.resource;

import io.ib67.prts.Perm;
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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
        as(alice).get("/api/admin/permission").then().statusCode(403);
    }

    @Test
    void theStatsCountEverything() {
        var project = fixtures.createProject("mine", alice);
        var archived = fixtures.createProject("old", alice);
        fixtures.archive(archived);
        var small = fixtures.createResourceClass("small", null);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createArtifact(job, "out.txt");
        fixtures.createWorker("w1");

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
                .body("storage.artifacts", equalTo(1))
                .body("storage.bytes", equalTo(1));
    }

    @Test
    void theProjectListingCountsMembersJobsAndQueue() {
        var project = fixtures.createProject("mine", alice);
        var small = fixtures.createResourceClass("small", null);
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
        fixtures.createResourceClass("small", null);

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

    /** Global templates require a global resource class. */
    @Test
    void aGlobalTemplateNeedsAGlobalResourceClass() {
        var project = fixtures.createProject("mine");
        fixtures.createResourceClass("small", project);

        as(admin).contentType(ContentType.JSON).body(Map.of(
                        "name", "shared", "resourceClass", "small", "spec", Map.of("image", "alpine")))
                .post("/api/admin/template").then()
                .statusCode(404)
                .body("message", equalTo("no such global resource class: small"));
    }

    @Test
    void aGlobalTemplateCannotMountVolumes() {
        fixtures.createResourceClass("small", null);
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
        var small = fixtures.createResourceClass("small", null);
        var theirs = fixtures.createTemplate("build", project, small);

        as(admin).get("/api/admin/template").then().body("$", empty());
        as(admin).delete("/api/admin/template/{t}", theirs).then().statusCode(404);
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
