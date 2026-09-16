package io.ib67.prts.job.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.project.ProjectResource;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Permission and access control tests for {@link ProjectResource}.
 */
@QuarkusTest
@Tag("e2e")
class ProjectResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private UUID project;
    private Fixtures.Actor alice;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.createProject("mine");
        alice = fixtures.createActor("alice");
    }

    /**
     * Verifies that requests without credentials are rejected when dev auth is inactive.
     */
    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/project").then().statusCode(401);
    }

    /**
     * The same aggregation the admin dashboard draws, behind the project's own read gate — an
     * ordinary member has no {@code admin:all} to reach {@code /admin/stats} with.
     */
    @Test
    void aViewerReadsTheProjectsOwnStatistics() {
        var small = fixtures.createResourceClass("small");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createTask(project, alice, "release", null);
        fixtures.createVolume(project, fixtures.createWorker("w1"), "cache");

        as(alice).get("/api/project/{p}/stats", project).then()
                .statusCode(200)
                .body("jobs.byState.SUCCESS", equalTo(1))
                .body("jobs.byState.keySet()", containsInAnyOrder(
                        "PENDING", "RUNNING", "FAILED", "SUCCESS", "CANCELLED"))
                .body("jobs.dailyLast90d", hasSize(90))
                .body("jobs.dailyLast90d[89].success", equalTo(1))
                // The wide heatmap's series: a month of hours, the last of which is the current one.
                .body("jobs.hourlyLast30d", hasSize(720))
                .body("jobs.hourlyLast30d[719].success", equalTo(1))
                .body("jobs.hourlyLast30d[0].success", equalTo(0))
                .body("tasks.byState.OPEN", equalTo(1))
                .body("storage.volumes", equalTo(1))
                .body("storage.volumeBytes", equalTo(1024));
    }

    /**
     * The per-project catalogue: the shared classes plus the ones an admin opened to this project.
     * A form offering anything else would draw a picker the submit refuses.
     */
    @Test
    void aViewerSeesTheClassesTheProjectMayActuallyUse() {
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createResourceClass("small");
        var huge = fixtures.createResourceClass("huge", false);

        as(alice).get("/api/project/{p}/resource-class", project).then()
                .statusCode(200)
                .body("items.name", contains("small"))
                .body("items[0].shared", equalTo(true))
                .body("total", equalTo(1));

        fixtures.allowResourceClass(project, huge);
        as(alice).get("/api/project/{p}/resource-class", project).then()
                .statusCode(200)
                .body("items.name", contains("huge", "small"))
                .body("total", equalTo(2));

        // The whole catalogue stays readable; only what the project may name is narrowed.
        as(alice).get("/api/resource-class").then()
                .statusCode(200)
                .body("items.name", contains("huge", "small"));
    }

    @Test
    void aStrangerCannotSeeAProjectsClasses() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/resource-class", project)
                .then().statusCode(403);
    }

    @Test
    void anotherProjectsWorkIsNotCountedHere() {
        var other = fixtures.createProject("theirs");
        var small = fixtures.createResourceClass("small");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createJob(other, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/stats", project).then()
                .statusCode(200)
                .body("jobs.byState.SUCCESS", equalTo(0));
    }

    @Test
    void aStrangerCannotReadTheStatistics() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/stats", project)
                .then().statusCode(403);
    }

    @Test
    void anUnknownTokenIsRejected() {
        given().header("Authorization", "Bearer prts_nosuchtoken")
                .get("/api/project").then().statusCode(401);
    }

    @Test
    void aPersonalAccessTokenAuthenticates() {
        as(alice).get("/api/project").then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void theListHoldsOnlyMyMemberships() {
        fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).get("/api/project").then()
                .statusCode(200)
                .body("name", contains("mine"))
                .body("role", contains("OWNER"));
    }

    @Test
    void aViewerCanReadTheProject() {
        var bob = fixtures.createActor("bob");
        var bobs = fixtures.createProject("theirs", bob);
        fixtures.join(alice, bobs, ProjectRole.VIEWER);

        as(alice).get("/api/project/{id}", bobs).then()
                .statusCode(200)
                .body("name", equalTo("theirs"))
                .body("role", equalTo("VIEWER"))
                .body("access", equalTo("MEMBER"))
                .body("members.name", containsInAnyOrder("alice", "bob"))
                .body("jobs.total", equalTo(0))
                .body("jobs.queued", equalTo(0));
    }

    @Test
    void aMemberReadsTheirOwnRole() {
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("role", equalTo("MEMBER"))
                .body("access", equalTo("MEMBER"));
    }

    /** Verifies that non-members are refused with 403 Forbidden. */
    @Test
    void aStrangerCannotReadTheProject() {
        as(alice).get("/api/project/{id}", project).then()
                .statusCode(403)
                .body("message", equalTo("missing permission: project:read"));
    }

    /** Non-members receive 403 for non-existent projects to prevent project enumeration. */
    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(alice).get("/api/project/{id}", UUID.randomUUID()).then().statusCode(403);
    }

    /** Admins bypass project membership checks and report ADMIN access level. */
    @Test
    void anAdminCanReadAProjectTheyAreNotIn() {
        fixtures.makeAdmin(alice);

        as(alice).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("role", equalTo("NONE"))
                .body("access", equalTo("ADMIN"));
    }

    /** Admins receive 404 when a project does not exist. */
    @Test
    void anAdminIsToldWhenTheProjectDoesNotExist() {
        fixtures.makeAdmin(alice);

        as(alice).get("/api/project/{id}", UUID.randomUUID()).then().statusCode(404);
    }

    /**
     * Verifies that permission grants and revocations take effect immediately by invalidating user permission caches.
     */
    @Test
    void aGrantTakesEffectAtOnceAndSoDoesItsRevocation() {
        as(alice).get("/api/project/{id}", project).then().statusCode(403);

        fixtures.grant(alice, Perm.PROJECT_READ, project);
        as(alice).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("role", equalTo("NONE"))
                .body("access", equalTo("PERMISSION"));

        fixtures.revoke(alice, Perm.PROJECT_READ, project);
        as(alice).get("/api/project/{id}", project).then().statusCode(403);
    }

    @Test
    void aGrantedUserOpensAProjectAndOwnsIt() {
        fixtures.grant(alice, Perm.PROJECT_CREATE, null);

        var opened = as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "  fresh  ", "description", "  what it is for  "))
                .post("/api/project").then()
                .statusCode(201)
                .body("name", equalTo("fresh"))
                .body("description", equalTo("what it is for"))
                .body("role", equalTo("OWNER"))
                .body("archivedAt", nullValue())
                .extract().path("id");

        as(alice).get("/api/project/{id}", opened).then()
                .statusCode(200)
                .body("role", equalTo("OWNER"))
                .body("members.name", contains("alice"));
    }

    @Test
    void openingAProjectNeedsThePermission() {
        as(alice).contentType(ContentType.JSON).body(Map.of("name", "fresh"))
                .post("/api/project").then()
                .statusCode(403)
                .body("message", equalTo("missing permission: " + Perm.PROJECT_CREATE.permission()));
    }

    @Test
    void anAdminOpensAProjectWithoutTheGrant() {
        fixtures.makeAdmin(alice);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "fresh"))
                .post("/api/project").then().statusCode(201);
    }

    @Test
    void aBlankNameCannotOpenAProject() {
        fixtures.grant(alice, Perm.PROJECT_CREATE, null);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "  "))
                .post("/api/project").then()
                .statusCode(400)
                .body("message", equalTo("name is required"));
    }

    /** Sub-accounts cannot own projects and therefore cannot create new projects. */
    @Test
    void aSubAccountCannotOpenAProject() {
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);
        fixtures.grant(ci, Perm.PROJECT_CREATE, null);

        as(ci).contentType(ContentType.JSON).body(Map.of("name", "fresh"))
                .post("/api/project").then().statusCode(409);
    }

    @Test
    void anOwnerHandsTheProjectOverAndStepsDown() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("userId", bob.id().toString()))
                .post("/api/project/{p}/transfer", project).then()
                .statusCode(200)
                .body("userId", equalTo(bob.id().toString()))
                .body("role", equalTo("OWNER"));

        as(bob).get("/api/project/{id}", project).then().body("role", equalTo("OWNER"));
        as(alice).get("/api/project/{id}", project).then().body("role", equalTo("MEMBER"));
    }

    @Test
    void transferringToSomeoneOutsideTheProjectIsNotFound() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("userId", bob.id().toString()))
                .post("/api/project/{p}/transfer", project).then()
                .statusCode(404)
                .body("message", equalTo("not a member of project " + project + ": " + bob.id()));
    }

    @Test
    void transferringToMyselfIsRejected() {
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("userId", alice.id().toString()))
                .post("/api/project/{p}/transfer", project).then()
                .statusCode(400)
                .body("message", equalTo("already the transfer target: " + alice.id()));
    }

    @Test
    void aMemberCannotTransferTheProject() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("userId", bob.id().toString()))
                .post("/api/project/{p}/transfer", project).then().statusCode(403);
    }

    /** Administrators can transfer project ownership without being members of the project. */
    @Test
    void anAdminTransfersWithoutBeingAMember() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        fixtures.join(alice, project, ProjectRole.OWNER);
        var bob = fixtures.createActor("bob");
        fixtures.join(bob, project, ProjectRole.VIEWER);

        as(admin).contentType(ContentType.JSON).body(Map.of("userId", bob.id().toString()))
                .post("/api/project/{p}/transfer", project).then().statusCode(200);

        as(alice).get("/api/project/{id}", project).then().body("role", equalTo("OWNER"));
        as(bob).get("/api/project/{id}", project).then().body("role", equalTo("OWNER"));
    }

    @Test
    void anOwnerArchivesTheProjectAndItGoesReadOnly() {
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).post("/api/project/{p}/archive", project).then()
                .statusCode(200)
                .body("archivedAt", notNullValue());

        as(alice).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("archivedAt", notNullValue());
        as(alice).contentType(ContentType.JSON).body(Map.of("name", "renamed"))
                .patch("/api/project/{id}", project).then()
                .statusCode(409)
                .body("message", equalTo("project is archived: " + project));
    }

    /** Verifies that write operations across the project are rejected while archived. */
    @Test
    void archivingRefusesEveryWriteAcrossTheProject() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);
        fixtures.archive(project);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "VIEWER"))
                .put("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(409);
        as(alice).delete("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(409);
        as(alice).contentType(ContentType.JSON).body(Map.of("name", "TOKEN", "value", "v"))
                .post("/api/project/{p}/secret", project).then().statusCode(409);
        as(alice).contentType(ContentType.JSON).body(Map.of("name", "ci"))
                .post("/api/project/{p}/subaccount", project).then().statusCode(409);
    }

    /** Verifies that unarchiving succeeds and restores write operations. */
    @Test
    void unarchivingRestoresWrites() {
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.archive(project);

        as(alice).post("/api/project/{p}/unarchive", project).then()
                .statusCode(200)
                .body("archivedAt", nullValue());

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "renamed"))
                .patch("/api/project/{id}", project).then().statusCode(200);
    }

    /** Verifies that archived projects can still be deleted. */
    @Test
    void anArchivedProjectCanStillBeDeleted() {
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.archive(project);

        as(alice).delete("/api/project/{id}", project).then().statusCode(204);
    }

    @Test
    void aMemberCannotArchiveTheProject() {
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).post("/api/project/{p}/archive", project).then().statusCode(403);
    }

    @Test
    void anOwnerCanRenameTheProject() {
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "  renamed  "))
                .patch("/api/project/{id}", project).then()
                .statusCode(200)
                .body("id", equalTo(project.toString()))
                .body("name", equalTo("renamed"))
                .body("role", equalTo("OWNER"));
        as(alice).get("/api/project/{id}", project).then().body("name", equalTo("renamed"));
    }

    @Test
    void aMemberCannotRenameTheProject() {
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "renamed"))
                .patch("/api/project/{id}", project).then().statusCode(403);
    }

    @Test
    void aBlankNameIsRejected() {
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "   "))
                .patch("/api/project/{id}", project).then()
                .statusCode(400)
                .body("message", equalTo("name must not be blank and at most 200 characters"));
    }

    /** Verifies that updating project description leaves name unmodified. */
    @Test
    void anOwnerCanDescribeTheProjectWithoutRenamingIt() {
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("description", "  the one I own  "))
                .patch("/api/project/{id}", project).then()
                .statusCode(200)
                .body("name", equalTo("mine"))
                .body("description", equalTo("the one I own"));
        as(alice).get("/api/project/{id}", project).then()
                .body("description", equalTo("the one I own"));
    }

    @Test
    void aBlankDescriptionClearsIt() {
        var described = fixtures.createProject("described", "something", alice);

        as(alice).contentType(ContentType.JSON).body(Map.of("description", "   "))
                .patch("/api/project/{id}", described).then()
                .statusCode(200)
                .body("description", emptyString());
    }

    @Test
    void aPatchThatAsksForNothingIsRejected() {
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .patch("/api/project/{id}", project).then()
                .statusCode(400)
                .body("message", equalTo("name or description is required; a blank description clears it"));
    }

    /** Deleting a project cascades to its memberships. */
    @Test
    void anOwnerCanDeleteTheProject() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).delete("/api/project/{id}", project).then().statusCode(204);

        as(alice).get("/api/project").then().body("$", empty());
        as(alice).get("/api/project/{id}", project).then().statusCode(403);
        as(admin).get("/api/project/{id}", project).then().statusCode(404);
    }

    @Test
    void aMemberCannotDeleteTheProject() {
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).delete("/api/project/{id}", project).then().statusCode(403);
        as(alice).get("/api/project/{id}", project).then().statusCode(200);
    }

    @Test
    void deletingAProjectThatDoesNotExistIsNotFound() {
        fixtures.makeAdmin(alice);
        var absent = UUID.randomUUID();

        as(alice).delete("/api/project/{id}", absent).then()
                .statusCode(404)
                .body("message", equalTo("no such project: " + absent));
    }

    @Test
    void anOwnerAddsAMemberAndChangesTheirRole() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "MEMBER"))
                .put("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(200)
                .body("userId", equalTo(bob.id().toString()))
                .body("name", equalTo("bob"))
                .body("role", equalTo("MEMBER"));

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "VIEWER"))
                .put("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(200)
                .body("role", equalTo("VIEWER"));

        as(bob).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("role", equalTo("VIEWER"));
    }

    @Test
    void aMemberCannotManageRoles() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "MEMBER"))
                .put("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(403);
    }

    @Test
    void aRoleIsRequired() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .put("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(400)
                .body("message", equalTo("role is required"));
    }

    @Test
    void noneIsNotARoleToSet() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "NONE"))
                .put("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(400)
                .body("message", equalTo("NONE is the absence of a membership; delete the member instead"));
    }

    @Test
    void theLastOwnerCannotStepDown() {
        var solo = fixtures.createProject("solo", alice);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "MEMBER"))
                .put("/api/project/{p}/member/{u}", solo, alice.id()).then()
                .statusCode(409)
                .body("message", equalTo("the last owner of project " + solo + " cannot step down"));
        as(alice).get("/api/project/{id}", solo).then().body("role", equalTo("OWNER"));
    }

    /** Sub-accounts can only hold permissions, not project roles. */
    @Test
    void aSubAccountCannotBeGivenARole() {
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "MEMBER"))
                .put("/api/project/{p}/member/{u}", project, ci.id()).then()
                .statusCode(409)
                .body("message", equalTo("a sub-account cannot hold a project role: " + ci.id()));
    }

    @Test
    void anOwnerCanRemoveAMember() {
        var bob = fixtures.createActor("bob");
        var mine = fixtures.createProject("mine", alice);
        fixtures.join(bob, mine, ProjectRole.MEMBER);

        as(alice).delete("/api/project/{p}/member/{u}", mine, bob.id()).then().statusCode(204);

        as(alice).get("/api/project/{id}", mine).then().body("members.name", contains("alice"));
        as(bob).get("/api/project/{id}", mine).then().statusCode(403);
    }

    /** Any member can remove themselves from a project without administrative permissions. */
    @Test
    void aMemberCanLeave() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(bob).delete("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(204);

        as(bob).get("/api/project").then().body("$", empty());
    }

    /**
     * Removing other members requires PROJECT_MEMBER_MANAGE permission, failing with an error message on denial.
     */
    @Test
    void aMemberCannotRemoveAnotherMember() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(alice).delete("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(403)
                .body("message", equalTo("missing permission: " + Perm.PROJECT_MEMBER_MANAGE.permission()));
        as(bob).get("/api/project/{id}", project).then().statusCode(200);
    }

    @Test
    void removingSomeoneWhoIsNotAMemberIsNotFound() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).delete("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(404)
                .body("message", equalTo("not a member of project " + project + ": " + bob.id()));
    }

    @Test
    void theLastOwnerCannotLeave() {
        var solo = fixtures.createProject("solo", alice);

        as(alice).delete("/api/project/{p}/member/{u}", solo, alice.id()).then()
                .statusCode(409)
                .body("message", equalTo("the last owner of project " + solo + " cannot step down"));
        as(alice).get("/api/project/{id}", solo).then().body("role", equalTo("OWNER"));
    }

    /**
     * An owner setting a sub-account's grants has to be able to read the list to choose them from;
     * {@code /admin/permission} is the only other place that publishes it and costs {@code admin:all}.
     */
    @Test
    void anOwnerReadsThePermissionsTheyMayHandOut() {
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).get("/api/project/{p}/permission", project).then()
                .statusCode(200)
                .body("scope", everyItem(equalTo("project")))
                .body("permission", hasItem(Perm.JOB_CREATE.permission()))
                .body("permission", not(hasItem(Perm.ADMIN_OF_ALL.permission())))
                .body("permission", not(hasItem(Perm.PROJECT_CREATE.permission())))
                .body("findAll { it.banned == true }", empty());
    }

    /** The catalogue is what the sub-account write is gated on, not what a member may read. */
    @Test
    void aMemberIsRefusedThePermissionCatalogue() {
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.join(bob, project, ProjectRole.OWNER);

        as(alice).get("/api/project/{p}/permission", project).then().statusCode(403);
        as(bob).get("/api/project/{p}/permission", project).then().statusCode(200);
    }

    @Test
    void thePermissionCatalogueOfAProjectThatDoesNotExistIsNotFound() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).get("/api/project/{p}/permission", UUID.randomUUID()).then().statusCode(404);
    }
}
