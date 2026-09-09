package io.ib67.prts.agent.worker.resource;

import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.ProjectRole;
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
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests permission checks and administrative operations on {@link WorkerResource}.
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
        as(fixtures.createActor("alice")).get("/api/worker").then()
                .statusCode(403)
                .body(emptyString());
    }

    /** Project-level roles do not grant global worker management permissions. */
    @Test
    void aProjectOwnerIsStillNoAdmin() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).get("/api/worker").then().statusCode(403);
    }

    @Test
    void anAdminListsWorkers() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        fixtures.createWorker("w1");

        as(admin).get("/api/worker").then()
                .statusCode(200)
                .body("name", contains("w1"))
                .body("[0].disabled", equalTo(false))
                // No active WebSocket session exists, so connected is false.
                .body("[0].connected", equalTo(false))
                .body("[0].info", nullValue());
    }

    @Test
    void anAdminReadsOneWorker() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");

        as(admin).get("/api/worker/{id}", worker).then()
                .statusCode(200)
                .body("id", equalTo(worker.toString()))
                .body("name", equalTo("w1"));
    }

    @Test
    void aWorkerThatDoesNotExistIsNotFound() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).get("/api/worker/{id}", UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void anAdminCanDisableAndEnableAWorker() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");

        as(admin).post("/api/worker/{id}/disable", worker).then()
                .statusCode(200)
                .body("disabled", equalTo(true));
        as(admin).get("/api/worker/{id}", worker).then().body("disabled", equalTo(true));

        as(admin).post("/api/worker/{id}/enable", worker).then()
                .statusCode(200)
                .body("disabled", equalTo(false));
    }

    @Test
    void disablingAWorkerThatDoesNotExistIsNotFound() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).post("/api/worker/{id}/disable", UUID.randomUUID()).then()
                .statusCode(404)
                .body(emptyString());
    }

    @Test
    void anOrdinaryUserCannotDisableAWorker() {
        var worker = fixtures.createWorker("w1");

        as(fixtures.createActor("alice")).post("/api/worker/{id}/disable", worker)
                .then().statusCode(403);
    }

    @Test
    void anAdminRenamesAWorker() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");

        as(admin).contentType(ContentType.JSON).body(Map.of("name", "  builder-1  "))
                .patch("/api/worker/{id}", worker).then()
                .statusCode(200)
                .body("name", equalTo("builder-1"));
        as(admin).get("/api/worker/{id}", worker).then().body("name", equalTo("builder-1"));
    }

    @Test
    void renamingNeedsAName() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");

        as(admin).contentType(ContentType.JSON).body(Map.of("name", "   "))
                .patch("/api/worker/{id}", worker).then()
                .statusCode(400)
                .body("message", equalTo("name is required"));
    }

    @Test
    void anAdminDropsAnIdleWorker() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");

        as(admin).delete("/api/worker/{id}", worker).then().statusCode(204);

        as(admin).get("/api/worker/{id}", worker).then().statusCode(404);
    }

    /** Workers with active jobs cannot be deleted. */
    @Test
    void aWorkerWithUnfinishedJobsIsNotDropped() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");
        var project = fixtures.createProject("mine");
        var small = fixtures.createResourceClass("small", null);
        fixtures.createJob(project, admin, small, JobState.RUNNING, worker);

        as(admin).delete("/api/worker/{id}", worker).then()
                .statusCode(409)
                .body("message", equalTo("worker " + worker + " still has 1 unfinished job(s)"));
    }

    /** Workers hosting persistent volumes cannot be deleted. */
    @Test
    void aWorkerStillHostingVolumesIsNotDropped() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");
        fixtures.createVolume(fixtures.createProject("mine"), worker, "data");

        as(admin).delete("/api/worker/{id}", worker).then()
                .statusCode(409)
                .body("message", equalTo("worker " + worker + " still hosts 1 volume(s)"));
    }

    @Test
    void anAdminListsAWorkersUnfinishedJobs() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");
        var project = fixtures.createProject("mine");
        var small = fixtures.createResourceClass("small", null);
        fixtures.createJob(project, admin, small, JobState.RUNNING, worker);
        fixtures.createJob(project, admin, small, JobState.SUCCESS, worker);

        as(admin).get("/api/worker/{id}/job", worker).then()
                .statusCode(200)
                .body("state", contains("RUNNING"));
    }

    @Test
    void anAdminListsAWorkersVolumes() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");
        var project = fixtures.createProject("mine");
        fixtures.createVolume(project, worker, "data");

        as(admin).get("/api/worker/{id}/volume", worker).then()
                .statusCode(200)
                .body("name", contains("data"))
                .body("[0].projectName", equalTo("mine"))
                .body("[0].length", equalTo(1024));
    }

    /** Disconnecting an offline worker succeeds without error. */
    @Test
    void disconnectingAnOfflineWorkerIsHarmless() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var worker = fixtures.createWorker("w1");

        as(admin).post("/api/worker/{id}/disconnect", worker).then().statusCode(204);
        as(admin).get("/api/worker/{id}", worker).then().statusCode(200);
    }

    @Test
    void disconnectingAWorkerThatDoesNotExistIsNotFound() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).post("/api/worker/{id}/disconnect", UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void anOrdinaryUserCannotDropAWorker() {
        var worker = fixtures.createWorker("w1");

        as(fixtures.createActor("alice")).delete("/api/worker/{id}", worker).then().statusCode(403);
    }
}
