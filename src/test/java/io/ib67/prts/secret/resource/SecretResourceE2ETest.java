package io.ib67.prts.secret.resource;

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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * The permission matrix of {@link SecretResource}: reading names is a member's, managing is an owner's.
 */
@QuarkusTest
@Tag("e2e")
class SecretResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private UUID project;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.project("mine");
    }

    // ---- reading ----

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/project/{p}/secret", project).then().statusCode(401);
    }

    @Test
    void aStrangerCannotListSecrets() {
        as(fixtures.actor("mallory")).get("/api/project/{p}/secret", project)
                .then().statusCode(403);
    }

    /** PROJECT_SECRET_READ defaults to MEMBER, so a viewer is one rung short. */
    @Test
    void aViewerCannotListSecrets() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).get("/api/project/{p}/secret", project).then().statusCode(403);
    }

    @Test
    void aMemberListsNamesButNeverValues() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.secret(project, "TOKEN", "s3cret");

        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", contains("TOKEN"))
                // SecretView projects the metadata only; the sealed value has no field to leak into.
                .body("[0]", not(hasKey("value")))
                .body("[0].description", nullValue());
    }

    @Test
    void theListHoldsOnlyThisProjectsSecrets() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.join(alice, other, ProjectRole.MEMBER);
        fixtures.secret(project, "MINE", "a");
        fixtures.secret(other, "THEIRS", "b");

        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", contains("MINE"));
    }

    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(fixtures.actor("mallory")).get("/api/project/{p}/secret", UUID.randomUUID())
                .then().statusCode(403);
    }

    /** The interceptor cannot tell absent from forbidden; only an admin gets past it to the 404. */
    @Test
    void onlyAnAdminReachesTheNotFound() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);

        as(admin).get("/api/project/{p}/secret", UUID.randomUUID()).then()
                .statusCode(404)
                // NotFoundMapper answers the service's NoSuchElementException without a body.
                .body(emptyString());
    }

    // ---- managing ----

    @Test
    void aMemberCannotCreateASecret() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then().statusCode(403);
    }

    @Test
    void anOwnerCanCreateASecret() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "description", "  the token  ", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"))
                // The resource strips before storing.
                .body("description", equalTo("the token"));
    }

    /** PROJECT_SECRET_MANAGE granted outright stands in for the owner role a sub-account has not got. */
    @Test
    void anExplicitGrantManagesWithoutTheOwnerRole() {
        var alice = fixtures.actor("alice");
        fixtures.grant(alice, Perm.PROJECT_SECRET_MANAGE, project);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"));
    }

    @Test
    void aNameThatIsNoEnvironmentVariableIsRejected() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "9lives", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(400)
                .body("message", equalTo("name must match [A-Za-z_][A-Za-z0-9_]{0,63}"));
    }

    @Test
    void anEmptyValueIsRejected() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", ""))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(400)
                .body("message", equalTo("value is required"));
    }

    @Test
    void aDuplicateNameConflicts() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.secret(project, "TOKEN", "s3cret");

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "other"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(409)
                .body("message", equalTo("project " + project + " already has a secret named TOKEN"));
    }

    /** The same name in another project is a different secret; nothing is shared across projects. */
    @Test
    void theSameNameInAnotherProjectIsFree() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.join(alice, other, ProjectRole.OWNER);
        fixtures.secret(project, "TOKEN", "s3cret");

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "other"))
                .post("/api/project/{p}/secret", other).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"));

        // One row each: the create landed in `other` and left `project`'s untouched.
        as(alice).get("/api/project/{p}/secret", other).then()
                .statusCode(200)
                .body("name", contains("TOKEN"));
        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", contains("TOKEN"));
    }

    @Test
    void anOwnerCanUpdateASecret() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.secret(project, "TOKEN", "s3cret");

        as(alice).contentType(ContentType.JSON).body(Map.of("description", "rotated"))
                .patch("/api/project/{p}/secret/{n}", project, "TOKEN").then()
                .statusCode(200)
                .body("description", equalTo("rotated"));
    }

    /** Both fields absent means the caller asked for nothing, which is not the same as clearing. */
    @Test
    void anEmptyUpdateIsRejected() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.secret(project, "TOKEN", "s3cret");

        // HashMap, not Map.of: the nulls are the point.
        var body = new HashMap<String, String>();
        body.put("description", null);
        body.put("value", null);

        as(alice).contentType(ContentType.JSON).body(body)
                .patch("/api/project/{p}/secret/{n}", project, "TOKEN").then()
                .statusCode(400)
                .body("message",
                        equalTo("description or value is required; a blank description clears it"));
    }

    @Test
    void updatingASecretThatDoesNotExistIsNotFound() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("value", "s3cret"))
                .patch("/api/project/{p}/secret/{n}", project, "TOKEN").then()
                .statusCode(404)
                .body("message", equalTo("no such secret in project " + project + ": TOKEN"));
    }

    @Test
    void aMemberCannotDeleteASecret() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.secret(project, "TOKEN", "s3cret");

        as(alice).delete("/api/project/{p}/secret/{n}", project, "TOKEN").then().statusCode(403);
    }

    @Test
    void anOwnerCanDeleteASecret() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.secret(project, "TOKEN", "s3cret");

        // The endpoint returns void, so RESTEasy answers 204.
        as(alice).delete("/api/project/{p}/secret/{n}", project, "TOKEN").then().statusCode(204);
        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void deletingASecretThatDoesNotExistIsNotFound() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).delete("/api/project/{p}/secret/{n}", project, "TOKEN").then()
                .statusCode(404)
                .body("message", equalTo("no such secret in project " + project + ": TOKEN"));
    }

    /** A secret of another project must not be reachable through this project's path. */
    @Test
    void aSecretOfAnotherProjectIsNotFoundHere() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.secret(other, "THEIRS", "s3cret");

        as(alice).delete("/api/project/{p}/secret/{n}", project, "THEIRS").then().statusCode(404);
    }

    @Test
    void anAdminManagesAProjectTheyAreNotIn() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);

        as(admin).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"));
    }
}
