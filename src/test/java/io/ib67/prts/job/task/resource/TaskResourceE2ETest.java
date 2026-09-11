package io.ib67.prts.job.task.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.job.JobSpec;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
        allowScopeWrites(member);
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
        allowScopeWrites(member);
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
                .body("message", equalTo(
                        "name, description, trackedAt or scope is required; a blank value clears it"));
    }

    @Test
    void aTaskCarriesADescriptionAndItsSource() {
        var id = UUID.fromString(as(member).contentType(ContentType.JSON)
                .body(Map.of(
                        "name", "pr-42",
                        "description", "rebuild the index",
                        "trackedAt", "https://example.invalid/pr/42"))
                .post(tasks()).then()
                .statusCode(201)
                .body("description", equalTo("rebuild the index"))
                .body("trackedAt", equalTo("https://example.invalid/pr/42"))
                .extract().path("id"));

        as(viewer).get(tasks() + "/" + id).then()
                .statusCode(200)
                .body("task.description", equalTo("rebuild the index"))
                .body("task.trackedAt", equalTo("https://example.invalid/pr/42"));
    }

    /** Both default to empty, and a blank edit clears them again. */
    @Test
    void describingIsOptionalAndReversible() {
        var id = openTask("pr-42");

        as(viewer).get(tasks() + "/" + id).then()
                .statusCode(200)
                .body("task.description", equalTo(""))
                .body("task.trackedAt", equalTo(""));

        as(member).contentType(ContentType.JSON)
                .body(Map.of("description", "why", "trackedAt", "https://example.invalid/issue/1"))
                .patch(tasks() + "/" + id).then()
                .statusCode(200)
                .body("description", equalTo("why"))
                .body("name", equalTo("pr-42"));

        as(member).contentType(ContentType.JSON)
                .body(Map.of("description", "  ", "trackedAt", ""))
                .patch(tasks() + "/" + id).then()
                .statusCode(200)
                .body("description", equalTo(""))
                .body("trackedAt", equalTo(""));
    }

    @Test
    void aSourceThatIsNotAnHttpUrlIsRejected() {
        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "pr-42", "trackedAt", "example.invalid/pr/42"))
                .post(tasks()).then()
                .statusCode(400)
                .body("message", equalTo("trackedAt must be an http or https URL"));
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

    /**
     * The control plane forwards a mount point to a worker that binds it, so it refuses anything that
     * is not a plain absolute path rather than leaving the traversal to be resolved there.
     */
    @ParameterizedTest
    @ValueSource(strings = {"data", "/../etc", "/data/../../etc", "/data/./x", "/data/", "/data//x", "/"})
    void anUnusableMountPointIsRejected(String mountPoint) {
        var volume = fixtures.createVolume(project, fixtures.createWorker("w1"), "cache");
        var id = openTask("pr-42");

        as(member).contentType(ContentType.JSON)
                .body(Map.of("mountPoint", mountPoint))
                .put(tasks() + "/" + id + "/volume/" + volume).then()
                .statusCode(400)
                .body("message", equalTo(JobSpec.VolumeSpec.MOUNT_POINT_REJECTED));
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

    /**
     * A task's scope reaches every job under it without passing the override gate, so writing one is
     * charged what overriding the same field would cost. `task:manage` alone is not enough.
     */
    @ParameterizedTest
    @MethodSource("gatedScopeFields")
    void aMemberCannotWriteAScopeFieldTheyCouldNotOverride(Map<String, Object> scope, Perm needed) {
        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "pr-42", "scope", scope))
                .post(tasks()).then()
                .statusCode(403)
                .body("message", equalTo("missing permission: " + needed.permission()));

        fixtures.grant(member, needed, project);
        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "pr-42", "scope", scope))
                .post(tasks()).then()
                .statusCode(201);
    }

    private static Stream<Arguments> gatedScopeFields() {
        return Stream.of(
                Arguments.of(Map.of("environment", Map.of("SHARED", "1")), Perm.JOB_SPEC_ENVIRONMENT),
                Arguments.of(Map.of("labels", Map.of("tier", "core")), Perm.JOB_SPEC_LABELS),
                Arguments.of(Map.of("resourceClass", "small"), Perm.JOB_RESOURCE_CLASS));
    }

    @Test
    void editingATaskToCarryAScopeIsGatedToo() {
        var id = openTask("pr-42");

        as(member).contentType(ContentType.JSON)
                .body(Map.of("scope", Map.of("resourceClass", "large")))
                .patch(tasks() + "/" + id).then()
                .statusCode(403)
                .body("message", equalTo("missing permission: " + Perm.JOB_RESOURCE_CLASS.permission()));
    }

    /** Absent and empty are the same value in a scope, so contributing nothing costs nothing. */
    @Test
    void aScopeThatContributesNothingCostsNothing() {
        as(member).contentType(ContentType.JSON)
                .body(Map.of("name", "pr-42", "scope", Map.of("environment", Map.of(), "labels", Map.of())))
                .post(tasks()).then()
                .statusCode(201);
    }

    private void allowScopeWrites(Fixtures.Actor actor) {
        fixtures.grant(actor, Perm.JOB_SPEC_ENVIRONMENT, project);
        fixtures.grant(actor, Perm.JOB_SPEC_LABELS, project);
        fixtures.grant(actor, Perm.JOB_RESOURCE_CLASS, project);
    }

    private void mount(UUID taskId, UUID volumeId, String mountPoint) {
        as(member).contentType(ContentType.JSON)
                .body(Map.of("mountPoint", mountPoint))
                .put(tasks() + "/" + taskId + "/volume/" + volumeId).then()
                .statusCode(200)
                .body("mountPoint", equalTo(mountPoint));
    }
}
