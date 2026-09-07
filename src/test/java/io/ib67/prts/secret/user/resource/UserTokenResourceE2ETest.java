package io.ib67.prts.secret.user.resource;

import io.ib67.prts.project.entity.ProjectRole;
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
 * {@link UserTokenResource} carries no {@code @RequirePermission}: a caller may manage their own token
 * and nobody else's, and a sub-account may not manage even that.
 *
 * <p>Its "no access token has been issued" branch is unreachable here. Reaching the route at all means
 * authenticating, and with OIDC off under {@code %test} the only way in is the token whose absence the
 * branch reports.
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
        as(fixtures.actor("alice")).get("/api/user/token").then()
                .statusCode(200)
                .body("issuedAt", notNullValue())
                // AccessTokenView holds the timestamp alone; the plaintext is never recoverable.
                .body("token", nullValue());
    }

    @Test
    void issuingReturnsAWorkingToken() {
        var alice = fixtures.actor("alice");

        var token = as(alice).put("/api/user/token").then()
                .statusCode(200)
                .body("token", startsWith("prts_"))
                .body("issuedAt", notNullValue())
                .extract().path("token").toString();

        given().header("Authorization", "Bearer " + token)
                .get("/api/user/token").then().statusCode(200);
    }

    /** One hash per user, so rotating leaves whoever holds the previous plaintext locked out. */
    @Test
    void issuingRetiresTheOldToken() {
        var alice = fixtures.actor("alice");

        as(alice).put("/api/user/token").then().statusCode(200);

        as(alice).get("/api/user/token").then().statusCode(401);
    }

    /**
     * A sub-account's token belongs to the project that minted it, so it cannot rotate itself out of
     * its owner's reach. Unlike the interceptor's bodiless 403 this is a
     * {@code jakarta.ws.rs.ForbiddenException}, so {@code ClientErrorMapper} explains it.
     */
    @Test
    void aSubAccountCannotReadItsOwnToken() {
        var alice = fixtures.actor("alice");
        var project = fixtures.project("mine");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(ci).get("/api/user/token").then()
                .statusCode(403)
                .body("message", equalTo("a sub-account's token is managed by its project's owner"));
    }

    @Test
    void aSubAccountCannotRotateItsOwnToken() {
        var alice = fixtures.actor("alice");
        var project = fixtures.project("mine");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(ci).put("/api/user/token").then()
                .statusCode(403)
                .body("message", equalTo("a sub-account's token is managed by its project's owner"));
    }
}
