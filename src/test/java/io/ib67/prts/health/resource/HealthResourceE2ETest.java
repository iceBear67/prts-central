package io.ib67.prts.health.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests for {@link HealthResource}, which exists to answer callers that hold no credentials.
 */
@QuarkusTest
@Tag("e2e")
class HealthResourceE2ETest {

    /** The point of the endpoint: no token, no session cookie, still a 204. */
    @Test
    void anUncredentialedRequestIsAnswered() {
        given().get("/api/health").then()
                .statusCode(204)
                .body(equalTo(""));
    }
}
