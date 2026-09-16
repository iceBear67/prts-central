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
import static org.hamcrest.Matchers.contains;
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
                .body("createdBy.id", equalTo(member.id().toString()))
                .body("createdBy.name", equalTo("member"))
                .body("closedAt", nullValue())
                .body("id", notNullValue())
                .body("createdAt", notNullValue());
    }

    @Test
    void aViewerMayReadButNotOpen() {
        openTask("pr-42");

        as(viewer).get(tasks()).then().statusCode(200).body("items", hasSize(1));
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

    /** Verifies that updating task scope replaces the entire scope configuration rather than merging. */
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

    /** Verifies that description and trackedAt fields can be cleared via empty values. */
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

    /** Verifies that a volume can be mounted by multiple tasks at different mount points. */
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

    /** Verifies that closing a task unmounts its volumes without deleting the volumes or affecting other tasks. */
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
                .body("items", hasSize(1))
                .body("items[0].id", equalTo(volume.toString()));
    }

    @Test
    void detachingLeavesTheVolumeAlone() {
        var worker = fixtures.createWorker("w1");
        var volume = fixtures.createVolume(project, worker, "cache");
        var id = openTask("pr-42");
        mount(id, volume, "/data");

        as(member).delete(tasks() + "/" + id + "/volume/" + volume).then().statusCode(204);

        as(viewer).get(tasks() + "/" + id + "/volume").then().statusCode(200).body("$", empty());
        as(viewer).get("/api/project/" + project + "/volume").then()
                .statusCode(200).body("items", hasSize(1));
    }

    /**
     * {@code task_volume} is many-to-many and the delete refuses while any mount remains, so "which
     * tasks mount this" is the question an operator has while looking at a volume they want gone.
     */
    @Test
    void aVolumeNamesTheTasksMountingIt() {
        var volume = fixtures.createVolume(project, fixtures.createWorker("w1"), "cache");
        var id = openTask("pr-42");
        mount(id, volume, "/data");

        as(viewer).get("/api/project/" + project + "/volume/" + volume).then()
                .statusCode(200)
                .body("volume.id", equalTo(volume.toString()))
                .body("volume.name", equalTo("cache"))
                .body("mounts", hasSize(1))
                .body("mounts[0].taskId", equalTo(id.toString()))
                .body("mounts[0].taskName", equalTo("pr-42"))
                .body("mounts[0].mountPoint", equalTo("/data"));

        as(viewer).get("/api/project/" + project + "/volume/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void theTaskListingIsSearchableAndNarrowsByState() {
        openTask("flaky login test");
        var closing = openTask("release");
        as(member).delete(tasks() + "/" + closing).then().statusCode(200);

        as(viewer).queryParam("query", "FLAKY").get(tasks()).then()
                .statusCode(200)
                .body("items.name", contains("flaky login test"))
                .body("total", equalTo(1));
        as(viewer).queryParam("state", "CLOSED").get(tasks()).then()
                .statusCode(200)
                .body("items.name", contains("release"));
    }

    /** Verifies that mounting volumes located on different workers to the same task is rejected. */
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

    /** Verifies that invalid or non-normalized mount points are rejected. */
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
     * Verifies that defining scope fields requires corresponding job override permissions in addition to task:manage.
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

    /** Verifies that an empty task scope does not require override permissions. */
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
