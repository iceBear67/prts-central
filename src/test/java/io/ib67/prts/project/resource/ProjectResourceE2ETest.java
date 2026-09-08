package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
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
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.join(bob, project, ProjectRole.OWNER);

        as(alice).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("name", equalTo("mine"))
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

    /** Verifies that non-members receive an empty 403 Forbidden. */
    @Test
    void aStrangerCannotReadTheProject() {
        as(alice).get("/api/project/{id}", project).then()
                .statusCode(403)
                .body(emptyString());
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

        var opened = as(alice).contentType(ContentType.JSON).body(Map.of("name", "  fresh  "))
                .post("/api/project").then()
                .statusCode(201)
                .body("name", equalTo("fresh"))
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

    /** Sub-accounts hold permissions but never a project role, so they cannot own a new project. */
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

    /** An admin transferring from outside the project moves only the target. */
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

    /** Everything a project can be written through is refused while it is archived. */
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

    /** Unarchiving is the one write an archived project still takes. */
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

    /** Deleting is the other, so an archived project is not stuck. */
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
                .body("message", equalTo("name is required"));
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
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "MEMBER"))
                .put("/api/project/{p}/member/{u}", project, alice.id()).then()
                .statusCode(409)
                .body("message", equalTo("the last owner of project " + project + " cannot step down"));
        as(alice).get("/api/project/{id}", project).then().body("role", equalTo("OWNER"));
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
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(alice).delete("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(204);

        as(alice).get("/api/project/{id}", project).then().body("members.name", contains("alice"));
        as(bob).get("/api/project/{id}", project).then().statusCode(403);
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
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).delete("/api/project/{p}/member/{u}", project, alice.id()).then()
                .statusCode(409)
                .body("message", equalTo("the last owner of project " + project + " cannot step down"));
        as(alice).get("/api/project/{id}", project).then().body("role", equalTo("OWNER"));
    }
}
