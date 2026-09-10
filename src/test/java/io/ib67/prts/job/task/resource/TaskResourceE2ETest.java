package io.ib67.prts.job.task.resource;

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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Task lifecycle, scope editing and volume mounting over HTTP.
 */
@QuarkusTest
@Tag("e2e")
class TaskResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private UUID project;
    private Fixtures.Actor owner;
    private Fixtures.Actor member;
    private Fixtures.Actor viewer;
    private Fixtures.Actor outsider;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        owner = fixtures.createActor("owner");
        project = fixtures.createProject("mine", owner);
        member = fixtures.createActor("member");
        viewer = fixtures.createActor("viewer");
        outsider = fixtures.createActor("outsider");
        fixtures.join(member, project, ProjectRole.MEMBER);
        fixtures.join(viewer, project, ProjectRole.VIEWER);
    }

    private String tasks() {
        return "/api/project/" + project + "/task";
    }

    private UUID openTask(String name) {
        return UUID.fromString(as(member).contentType(ContentType.JSON)
                .body(Map.of("name", name))
                .post(tasks()).then()
                .statusCode(201)
                .extract().path("id"));
    }

    @Test
    void aMemberOpensATaskAndItStartsOpen() {
        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "pr-42"))
                .post(tasks()).then()
                .statusCode(201)
                .body("name", equalTo("pr-42"))
                .body("state", equalTo("OPEN"))
                .body("projectId", equalTo(project.toString()))
                .body("closedAt", nullValue())
                .body("id", notNullValue())
                .body("createdAt", notNullValue());
    }

    @Test
    void aViewerMayReadButNotOpen() {
        openTask("pr-42");

        as(viewer).get(tasks()).then().statusCode(200).body("$", hasSize(1));
        as(viewer).contentType(ContentType.JSON)
                .body(Map.of("name", "nope"))
                .post(tasks()).then().statusCode(403);
    }

    @Test
    void aNonMemberSeesNothing() {
        as(outsider).get(tasks()).then().statusCode(403);
    }

    @Test
    void aNamelessTaskIsRejected() {
        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "  "))
                .post(tasks()).then()
                .statusCode(400)
                .body("message", equalTo("name is required"));
    }

    @Test
    void theScopeRoundTrips() {
        var id = UUID.fromString(as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "pr-42", "scope", Map.of(
                        "environment", Map.of("SHARED", "1"),
                        "labels", Map.of("tier", "core"),
                        "resourceClass", "small")))
                .post(tasks()).then()
                .statusCode(201)
                .extract().path("id"));

        as(viewer).get(tasks() + "/" + id).then()
                .statusCode(200)
                .body("task.scope.environment.SHARED", equalTo("1"))
                .body("task.scope.labels.tier", equalTo("core"))
                .body("task.scope.resourceClass", equalTo("small"))
                .body("volumes", empty());
    }

    /** A new scope replaces the old one outright rather than merging into it. */
    @Test
    void editingTheScopeReplacesIt() {
        var id = openTask("pr-42");

        as(member).contentType(ContentType.JSON)
                .body(Map.of("scope", Map.of("environment", Map.of("B", "2"))))
                .patch(tasks() + "/" + id).then()
                .statusCode(200)
                .body("scope.environment.B", equalTo("2"));

        as(member).contentType(ContentType.JSON)
                .body(Map.of("scope", Map.of("environment", Map.of("C", "3"))))
                .patch(tasks() + "/" + id).then()
                .statusCode(200)
                .body("scope.environment.B", nullValue())
                .body("scope.environment.C", equalTo("3"));
    }

    @Test
    void anEmptyEditIsRejected() {
        var id = openTask("pr-42");

        as(member).contentType(ContentType.JSON)
                .body(Map.of())
                .patch(tasks() + "/" + id).then()
                .statusCode(400)
                .body("message", equalTo("name or scope is required"));
    }

    @Test
    void closingLeavesTheTaskOnRecord() {
        var id = openTask("pr-42");

        as(member).delete(tasks() + "/" + id).then()
                .statusCode(200)
                .body("state", equalTo("CLOSED"))
                .body("closedAt", notNullValue());

        as(viewer).get(tasks() + "/" + id).then()
                .statusCode(200)
                .body("task.state", equalTo("CLOSED"));
    }

    @Test
    void aClosedTaskRefusesNewJobsAndEdits() {
        var id = openTask("pr-42");
        as(member).delete(tasks() + "/" + id).then().statusCode(200);

        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "renamed"))
                .patch(tasks() + "/" + id).then().statusCode(409);

        var template = fixtures.createTemplate("t", project, fixtures.createResourceClass("small"));
        as(member).contentType(ContentType.JSON)
                .body(Map.of("templateId", template.toString(), "taskId", id.toString()))
                .post("/api/project/" + project + "/job").then()
                .statusCode(409);
    }

    @Test
    void anArchivedProjectRefusesTaskWrites() {
        var id = openTask("pr-42");
        as(owner).post("/api/project/" + project + "/archive").then().statusCode(200);

        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "pr-43"))
                .post(tasks()).then().statusCode(409);
        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "renamed"))
                .patch(tasks() + "/" + id).then().statusCode(409);
        as(viewer).get(tasks()).then().statusCode(200);
    }

    @Test
    void aTaskOfAnotherProjectIsNotFound() {
        var other = fixtures.createProject("theirs");
        fixtures.join(member, other, ProjectRole.MEMBER);
        var id = openTask("pr-42");

        as(member).get("/api/project/" + other + "/task/" + id).then().statusCode(404);
    }

    /** One volume, two tasks, each mounting it where it likes. */
    @Test
    void aVolumeIsSharedByTwoTasksAtDifferentPaths() {
        var worker = fixtures.createWorker("w1");
        var volume = fixtures.createVolume(project, worker, "cache");
        var first = openTask("pr-42");
        var second = openTask("pr-43");

        mount(first, volume, "/data");
        mount(second, volume, "/mnt/cache");

        as(viewer).get(tasks() + "/" + first + "/volume").then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].mountPoint", equalTo("/data"))
                .body("[0].volumeId", equalTo(volume.toString()));
        as(viewer).get(tasks() + "/" + second + "/volume").then()
                .statusCode(200)
                .body("[0].mountPoint", equalTo("/mnt/cache"));
    }

    /** Closing a task drops its mounts; the volume and every other task's mount survive. */
    @Test
    void closingATaskOnlyUnmounts() {
        var worker = fixtures.createWorker("w1");
        var volume = fixtures.createVolume(project, worker, "cache");
        var first = openTask("pr-42");
        var second = openTask("pr-43");
        mount(first, volume, "/data");
        mount(second, volume, "/data");

        as(member).delete(tasks() + "/" + first).then().statusCode(200);

        as(viewer).get(tasks() + "/" + first + "/volume").then().statusCode(200).body("$", empty());
        as(viewer).get(tasks() + "/" + second + "/volume").then().statusCode(200).body("$", hasSize(1));
        as(viewer).get("/api/project/" + project + "/volume").then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].id", equalTo(volume.toString()));
    }

    @Test
    void detachingLeavesTheVolumeAlone() {
        var worker = fixtures.createWorker("w1");
        var volume = fixtures.createVolume(project, worker, "cache");
        var id = openTask("pr-42");
        mount(id, volume, "/data");

        as(member).delete(tasks() + "/" + id + "/volume/" + volume).then().statusCode(204);

        as(viewer).get(tasks() + "/" + id + "/volume").then().statusCode(200).body("$", empty());
        as(viewer).get("/api/project/" + project + "/volume").then().statusCode(200).body("$", hasSize(1));
    }

    /** A job can only be placed on a worker holding all of its volumes. */
    @Test
    void volumesOnASecondWorkerCannotJoinTheTask() {
        var here = fixtures.createVolume(project, fixtures.createWorker("w1"), "here");
        var there = fixtures.createVolume(project, fixtures.createWorker("w2"), "there");
        var id = openTask("pr-42");
        mount(id, here, "/data");

        as(member).contentType(ContentType.JSON)
                .body(Map.of("mountPoint", "/other"))
                .put(tasks() + "/" + id + "/volume/" + there).then()
                .statusCode(409);
    }

    @Test
    void twoVolumesCannotShareAMountPoint() {
        var worker = fixtures.createWorker("w1");
        var first = fixtures.createVolume(project, worker, "one");
        var second = fixtures.createVolume(project, worker, "two");
        var id = openTask("pr-42");
        mount(id, first, "/data");

        as(member).contentType(ContentType.JSON)
                .body(Map.of("mountPoint", "/data"))
                .put(tasks() + "/" + id + "/volume/" + second).then()
                .statusCode(409);
    }

    @Test
    void remountingMovesTheExistingMount() {
        var volume = fixtures.createVolume(project, fixtures.createWorker("w1"), "cache");
        var id = openTask("pr-42");
        mount(id, volume, "/data");

        mount(id, volume, "/moved");

        as(viewer).get(tasks() + "/" + id + "/volume").then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].mountPoint", equalTo("/moved"));
    }

    @Test
    void aRelativeMountPointIsRejected() {
        var volume = fixtures.createVolume(project, fixtures.createWorker("w1"), "cache");
        var id = openTask("pr-42");

        as(member).contentType(ContentType.JSON)
                .body(Map.of("mountPoint", "data"))
                .put(tasks() + "/" + id + "/volume/" + volume).then()
                .statusCode(400)
                .body("message", equalTo("mountPoint must be an absolute path"));
    }

    @Test
    void aVolumeOfAnotherProjectCannotBeMounted() {
        var other = fixtures.createProject("theirs");
        var theirs = fixtures.createVolume(other, fixtures.createWorker("w1"), "cache");
        var id = openTask("pr-42");

        as(member).contentType(ContentType.JSON)
                .body(Map.of("mountPoint", "/data"))
                .put(tasks() + "/" + id + "/volume/" + theirs).then()
                .statusCode(404);
    }

    private void mount(UUID taskId, UUID volumeId, String mountPoint) {
        as(member).contentType(ContentType.JSON)
                .body(Map.of("mountPoint", mountPoint))
                .put(tasks() + "/" + taskId + "/volume/" + volumeId).then()
                .statusCode(200)
                .body("mountPoint", equalTo(mountPoint));
    }
}
