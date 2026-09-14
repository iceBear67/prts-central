package io.ib67.prts.health.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end tests for unauthenticated health check endpoint {@link HealthResource}.
 */
@QuarkusTest
@Tag("e2e")
class HealthResourceE2ETest {

    /** Verifies that unauthenticated requests receive 204 No Content without credentials. */
    @Test
    void anUncredentialedRequestIsAnswered() {
        given().get("/api/health").then()
                .statusCode(204)
                .body(equalTo(""));
    }
}
