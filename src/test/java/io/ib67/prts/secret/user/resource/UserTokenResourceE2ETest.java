package io.ib67.prts.secret.user.resource;

import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.ib67.prts.testing.Fixtures.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests for {@link UserTokenResource}, which allows authenticated users to manage their personal access token.
 */
@QuarkusTest
@Tag("e2e")
class UserTokenResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
    }

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/user/token").then().statusCode(401);
    }

    @Test
    void aTokenHolderSeesWhenItWasIssuedAndNothingElse() {
        as(fixtures.createActor("alice")).get("/api/user/token").then()
                .statusCode(200)
                .body("issuedAt", notNullValue())
                // AccessTokenView exposes the issue timestamp and excludes the raw token.
                .body("token", nullValue());
    }

    @Test
    void issuingReturnsAWorkingToken() {
        var alice = fixtures.createActor("alice");

        var token = as(alice).put("/api/user/token").then()
                .statusCode(200)
                .body("token", startsWith("prts_"))
                .body("issuedAt", notNullValue())
                .extract().path("token").toString();

        given().header("Authorization", "Bearer " + token)
                .get("/api/user/token").then().statusCode(200);
    }

    /** Generating a new token invalidates the previously issued token. */
    @Test
    void issuingRetiresTheOldToken() {
        var alice = fixtures.createActor("alice");

        as(alice).put("/api/user/token").then().statusCode(200);

        as(alice).get("/api/user/token").then().statusCode(401);
    }

    /**
     * Sub-accounts cannot read or manage their tokens directly; they are managed by the project owner.
     */
    @Test
    void aSubAccountCannotReadItsOwnToken() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine", alice);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(ci).get("/api/user/token").then()
                .statusCode(403)
                .body("message", equalTo("a sub-account's token is managed by its project's owner"));
    }

    @Test
    void aSubAccountCannotRotateItsOwnToken() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine", alice);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(ci).put("/api/user/token").then()
                .statusCode(403)
                .body("message", equalTo("a sub-account's token is managed by its project's owner"));
    }
}
