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

/**
 * The permission matrix of {@link ProjectResource}, over the real authentication chain.
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
        project = fixtures.project("mine");
        alice = fixtures.actor("alice");
    }

    // ---- authentication ----

    /**
     * Also the guard on dev auto-login: {@code DevAuthMechanism} is {@code @IfBuildProfile("dev")}, so it
     * is not built here. Were that ever loosened, every case below would pass as an admin instead.
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

    // ---- reading ----

    @Test
    void theListHoldsOnlyMyMemberships() {
        fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).get("/api/project").then()
                .statusCode(200)
                .body("name", contains("mine"))
                .body("role", contains("OWNER"));
    }

    @Test
    void aViewerCanReadTheProject() {
        var bob = fixtures.actor("bob");
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

    /** The interceptor's 403 carries no body: nothing in it may hint at what was refused. */
    @Test
    void aStrangerCannotReadTheProject() {
        as(alice).get("/api/project/{id}", project).then()
                .statusCode(403)
                .body(emptyString());
    }

    /** A stranger must not be able to tell an existing project from one that never existed. */
    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(alice).get("/api/project/{id}", UUID.randomUUID()).then().statusCode(403);
    }

    /** ADMIN_OF_ALL is the bypass @RequirePermission grants, and it reports itself as the basis. */
    @Test
    void anAdminCanReadAProjectTheyAreNotIn() {
        fixtures.makeAdmin(alice);

        as(alice).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("role", equalTo("NONE"))
                .body("access", equalTo("ADMIN"));
    }

    /** Past the permission check the non-disclosure rule no longer applies. */
    @Test
    void anAdminIsToldWhenTheProjectDoesNotExist() {
        fixtures.makeAdmin(alice);

        as(alice).get("/api/project/{id}", UUID.randomUUID()).then().statusCode(404);
    }

    /**
     * Grants are cached per user for minutes, and both {@code grant} and {@code revoke} invalidate the
     * entry once their transaction commits. Warmed first, so a stale cache would be caught either way.
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

    // ---- renaming ----

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

    // ---- deleting ----

    /** The membership rows go with the project, so afterwards even the owner is a stranger to it. */
    @Test
    void anOwnerCanDeleteTheProject() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        fixtures.join(alice, project, ProjectRole.OWNER);

        // The endpoint returns void, so RESTEasy answers 204.
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

    // ---- member roles ----

    @Test
    void anOwnerAddsAMemberAndChangesTheirRole() {
        var bob = fixtures.actor("bob");
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
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "MEMBER"))
                .put("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(403);
    }

    @Test
    void aRoleIsRequired() {
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .put("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(400)
                .body("message", equalTo("role is required"));
    }

    @Test
    void noneIsNotARoleToSet() {
        var bob = fixtures.actor("bob");
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

    /** A sub-account holds permissions, never a role; the roster is for people. */
    @Test
    void aSubAccountCannotBeGivenARole() {
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON).body(Map.of("role", "MEMBER"))
                .put("/api/project/{p}/member/{u}", project, ci.id()).then()
                .statusCode(409)
                .body("message", equalTo("a sub-account cannot hold a project role: " + ci.id()));
    }

    // ---- removing members ----

    @Test
    void anOwnerCanRemoveAMember() {
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(alice).delete("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(204);

        as(alice).get("/api/project/{id}", project).then().body("members.name", contains("alice"));
        as(bob).get("/api/project/{id}", project).then().statusCode(403);
    }

    /** Leaving needs no permission of its own: {@code defaultValue = true} lets anyone in this far. */
    @Test
    void aMemberCanLeave() {
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(bob).delete("/api/project/{p}/member/{u}", project, bob.id()).then().statusCode(204);

        as(bob).get("/api/project").then().body("$", empty());
    }

    /**
     * The same route, so the check moves into the method; this one is a {@code jakarta.ws.rs}
     * ForbiddenException and so does carry a message, unlike the interceptor's.
     */
    @Test
    void aMemberCannotRemoveAnotherMember() {
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.join(bob, project, ProjectRole.MEMBER);

        as(alice).delete("/api/project/{p}/member/{u}", project, bob.id()).then()
                .statusCode(403)
                .body("message", equalTo("missing permission: " + Perm.PROJECT_MEMBER_MANAGE.permission()));
        as(bob).get("/api/project/{id}", project).then().statusCode(200);
    }

    @Test
    void removingSomeoneWhoIsNotAMemberIsNotFound() {
        var bob = fixtures.actor("bob");
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
