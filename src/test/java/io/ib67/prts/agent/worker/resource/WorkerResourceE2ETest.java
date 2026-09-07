package io.ib67.prts.agent.worker.resource;

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
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@link WorkerResource} is administrative throughout: its binding is {@link io.ib67.prts.Perm#ADMIN_OF_ALL},
 * which is global, so no project standing reaches it.
 */
@QuarkusTest
@Tag("e2e")
class WorkerResourceE2ETest {

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
        given().get("/api/worker").then().statusCode(401);
    }

    @Test
    void anOrdinaryUserCannotListWorkers() {
        as(fixtures.actor("alice")).get("/api/worker").then()
                .statusCode(403)
                .body(emptyString());
    }

    /** Owning a project buys nothing here; the permission is not project-scoped to begin with. */
    @Test
    void aProjectOwnerIsStillNoAdmin() {
        var alice = fixtures.actor("alice");
        var project = fixtures.project("mine");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).get("/api/worker").then().statusCode(403);
    }

    @Test
    void anAdminListsWorkers() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        fixtures.worker("w1");

        as(admin).get("/api/worker").then()
                .statusCode(200)
                .body("name", contains("w1"))
                .body("[0].disabled", equalTo(false))
                // The row is all there is: nothing holds a WebSocket session, so there is no live half.
                .body("[0].connected", equalTo(false))
                .body("[0].info", nullValue());
    }

    @Test
    void anAdminReadsOneWorker() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.worker("w1");

        as(admin).get("/api/worker/{id}", worker).then()
                .statusCode(200)
                .body("id", equalTo(worker.toString()))
                .body("name", equalTo("w1"));
    }

    @Test
    void aWorkerThatDoesNotExistIsNotFound() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);

        as(admin).get("/api/worker/{id}", UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void anAdminCanDisableAndEnableAWorker() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.worker("w1");

        as(admin).post("/api/worker/{id}/disable", worker).then()
                .statusCode(200)
                .body("disabled", equalTo(true));
        as(admin).get("/api/worker/{id}", worker).then().body("disabled", equalTo(true));

        as(admin).post("/api/worker/{id}/enable", worker).then()
                .statusCode(200)
                .body("disabled", equalTo(false));
    }

    /** {@code WorkerService.setDisabled} raises a {@code NoSuchElementException}, which has no body. */
    @Test
    void disablingAWorkerThatDoesNotExistIsNotFound() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);

        as(admin).post("/api/worker/{id}/disable", UUID.randomUUID()).then()
                .statusCode(404)
                .body(emptyString());
    }

    @Test
    void anOrdinaryUserCannotDisableAWorker() {
        var worker = fixtures.worker("w1");

        as(fixtures.actor("alice")).post("/api/worker/{id}/disable", worker)
                .then().statusCode(403);
    }
}
