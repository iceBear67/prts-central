package io.ib67.prts.secret.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.job.entity.ProjectRole;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Permission and access control tests for {@link SecretResource}.
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
        project = fixtures.createProject("mine");
    }

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/project/{p}/secret", project).then().statusCode(401);
    }

    @Test
    void aStrangerCannotListSecrets() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/secret", project)
                .then().statusCode(403);
    }

    /** PROJECT_SECRET_READ requires MEMBER role or above; viewers cannot list secrets. */
    @Test
    void aViewerCannotListSecrets() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).get("/api/project/{p}/secret", project).then().statusCode(403);
    }

    @Test
    void aMemberListsNamesButNeverValues() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.createSecret(project, "TOKEN", "s3cret");

        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", contains("TOKEN"))
                // SecretView only exposes metadata and excludes secret values.
                .body("[0]", not(hasKey("value")))
                .body("[0].description", nullValue());
    }

    @Test
    void theListHoldsOnlyThisProjectsSecrets() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.join(alice, other, ProjectRole.MEMBER);
        fixtures.createSecret(project, "MINE", "a");
        fixtures.createSecret(other, "THEIRS", "b");

        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", contains("MINE"));
    }

    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/secret", UUID.randomUUID())
                .then().statusCode(403);
    }

    /** Non-members receive 403 on non-existent projects, while admins receive 404. */
    @Test
    void onlyAnAdminReachesTheNotFound() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        var missing = UUID.randomUUID();

        as(admin).get("/api/project/{p}/secret", missing).then()
                .statusCode(404)
                .body("message", equalTo("no such project: " + missing));
    }

    @Test
    void aMemberCannotCreateASecret() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then().statusCode(403);
    }

    @Test
    void anOwnerCanCreateASecret() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "description", "  the token  ", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"))
                // Description is trimmed before saving.
                .body("description", equalTo("the token"));
    }

    /** Explicit PROJECT_SECRET_MANAGE grant allows managing secrets without the OWNER role. */
    @Test
    void anExplicitGrantManagesWithoutTheOwnerRole() {
        var alice = fixtures.createActor("alice");
        fixtures.grant(alice, Perm.PROJECT_SECRET_MANAGE, project);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"));
    }

    @Test
    void aNameThatIsNoEnvironmentVariableIsRejected() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "9lives", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(400)
                .body("message", equalTo("name must match [A-Za-z_][A-Za-z0-9_]{0,63}"));
    }

    @Test
    void anEmptyValueIsRejected() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", ""))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(400)
                .body("message", equalTo("value is required"));
    }

    @Test
    void aDuplicateNameConflicts() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.createSecret(project, "TOKEN", "s3cret");

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "other"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(409)
                .body("message", equalTo("project " + project + " already has a secret named TOKEN"));
    }

    /** Secret names are scoped to individual projects. */
    @Test
    void theSameNameInAnotherProjectIsFree() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs", alice);
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.createSecret(project, "TOKEN", "s3cret");

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "other"))
                .post("/api/project/{p}/secret", other).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"));

        as(alice).get("/api/project/{p}/secret", other).then()
                .statusCode(200)
                .body("name", contains("TOKEN"));
        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", contains("TOKEN"));
    }

    @Test
    void anOwnerCanUpdateASecret() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.createSecret(project, "TOKEN", "s3cret");

        as(alice).contentType(ContentType.JSON).body(Map.of("description", "rotated"))
                .patch("/api/project/{p}/secret/{n}", project, "TOKEN").then()
                .statusCode(200)
                .body("description", equalTo("rotated"));
    }

    /** Rejects requests where both description and value are omitted. */
    @Test
    void anEmptyUpdateIsRejected() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.createSecret(project, "TOKEN", "s3cret");

        // Use HashMap because Map.of does not permit null values.
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
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("value", "s3cret"))
                .patch("/api/project/{p}/secret/{n}", project, "TOKEN").then()
                .statusCode(404)
                .body("message", equalTo("no such secret in project " + project + ": TOKEN"));
    }

    @Test
    void aMemberCannotDeleteASecret() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.createSecret(project, "TOKEN", "s3cret");

        as(alice).delete("/api/project/{p}/secret/{n}", project, "TOKEN").then().statusCode(403);
    }

    @Test
    void anOwnerCanDeleteASecret() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.createSecret(project, "TOKEN", "s3cret");

        as(alice).delete("/api/project/{p}/secret/{n}", project, "TOKEN").then().statusCode(204);
        as(alice).get("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void deletingASecretThatDoesNotExistIsNotFound() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).delete("/api/project/{p}/secret/{n}", project, "TOKEN").then()
                .statusCode(404)
                .body("message", equalTo("no such secret in project " + project + ": TOKEN"));
    }

    /** Secrets belonging to other projects cannot be accessed or modified across project boundaries. */
    @Test
    void aSecretOfAnotherProjectIsNotFoundHere() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.createSecret(other, "THEIRS", "s3cret");

        as(alice).delete("/api/project/{p}/secret/{n}", project, "THEIRS").then().statusCode(404);
    }

    @Test
    void anAdminManagesAProjectTheyAreNotIn() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).contentType(ContentType.JSON)
                .body(Map.of("name", "TOKEN", "value", "s3cret"))
                .post("/api/project/{p}/secret", project).then()
                .statusCode(200)
                .body("name", equalTo("TOKEN"));
    }
}
