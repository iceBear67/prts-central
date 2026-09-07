package io.ib67.prts.project.resource;

import io.ib67.prts.project.entity.ProjectRole;
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
import static org.hamcrest.Matchers.empty;
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

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
    }

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
        as(fixtures.actor("alice")).get("/api/project").then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void theListHoldsOnlyMyMemberships() {
        var alice = fixtures.actor("alice");
        var mine = fixtures.project("mine");
        fixtures.project("theirs");
        fixtures.join(alice, mine, ProjectRole.OWNER);

        as(alice).get("/api/project").then()
                .statusCode(200)
                .body("name", contains("mine"))
                .body("role", contains("OWNER"));
    }

    @Test
    void aMemberCanReadTheProject() {
        var alice = fixtures.actor("alice");
        var project = fixtures.project("mine");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("name", equalTo("mine"))
                .body("role", equalTo("VIEWER"))
                .body("access", equalTo("MEMBER"));
    }

    @Test
    void aStrangerCannotReadTheProject() {
        var project = fixtures.project("theirs");

        as(fixtures.actor("mallory")).get("/api/project/{id}", project).then().statusCode(403);
    }

    /** A stranger must not be able to tell an existing project from one that never existed. */
    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(fixtures.actor("mallory")).get("/api/project/{id}", UUID.randomUUID())
                .then().statusCode(403);
    }

    /** ADMIN_OF_ALL is the bypass @RequirePermission grants, and it reports itself as the basis. */
    @Test
    void anAdminCanReadAProjectTheyAreNotIn() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        var project = fixtures.project("theirs");

        as(admin).get("/api/project/{id}", project).then()
                .statusCode(200)
                .body("role", equalTo("NONE"))
                .body("access", equalTo("ADMIN"));
    }
}
