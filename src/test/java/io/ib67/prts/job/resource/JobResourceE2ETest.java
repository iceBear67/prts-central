package io.ib67.prts.job.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.job.JobConfig;
import io.ib67.prts.job.JobResource;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.ib67.prts.user.UserService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests permission checks and endpoints of {@link JobResource}.
 */
@QuarkusTest
@Tag("e2e")
class JobResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;
    @Inject
    JobConfig jobConfig;
    @Inject
    UserService userService;

    private UUID project;
    private ResourceClass small;
    private UUID template;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.createProject("mine");
        small = fixtures.createResourceClass("small");
        template = fixtures.createTemplate("build", project, small);
    }

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/project/{p}/job", project).then().statusCode(401);
    }

    @Test
    void aStrangerCannotListJobs() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/job", project).then().statusCode(403);
    }

    /** Non-members receive 403 even if the project does not exist. */
    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/job", UUID.randomUUID())
                .then().statusCode(403);
    }

    /** Admins receive 404 when querying a nonexistent project. */
    @Test
    void anAdminIsToldWhenTheProjectDoesNotExist() {
        var root = fixtures.createActor("root");
        fixtures.makeAdmin(root);
        var absent = UUID.randomUUID();

        as(root).get("/api/project/{p}/job", absent).then().statusCode(404);
        as(root).get("/api/project/{p}/job/template", absent).then().statusCode(404);
    }

    @Test
    void aViewerCanListJobs() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items", empty())
                .body("total", equalTo(0));
    }

    /** Unplaced PENDING jobs without a worker are hidden from listings. */
    @Test
    void anUnplacedJobIsNotListed() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createJob(project, alice, small, JobState.PENDING, null);
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items.state", contains("SUCCESS"))
                .body("items.type", contains("job"))
                // The total counts what the listing would return, so the hidden row is out of both.
                .body("total", equalTo(1));
    }

    /** Pending job queue entries are listed alongside jobs, and counted in the same total. */
    @Test
    void aQueueEntryIsListedAlongsideJobs() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createQueuedJob(project, alice, template, "small");
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items.type", containsInAnyOrder("pending", "job"))
                // Viewers receive resource class metadata without the job request payload.
                .body("items.resourceClass", contains("small", "small"))
                .body("total", equalTo(2));
    }

    /**
     * The listing merges two tables, so one {@code state} has to narrow both — and a state only one
     * of them knows must select nothing from the other rather than leaving it unfiltered.
     */
    @Test
    void theStateNarrowsBothHalvesOfTheListing() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createJob(project, alice, small, JobState.FAILED, UUID.randomUUID());
        fixtures.createQueuedJob(project, alice, template, "small");

        as(alice).queryParam("state", "SUCCESS").get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items.type", contains("job"))
                .body("items.state", contains("SUCCESS"))
                .body("total", equalTo(1));
        as(alice).queryParam("state", "QUEUED").get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items.type", contains("pending"))
                .body("total", equalTo(1));
        as(alice).queryParam("state", "RUNNING").get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items", empty())
                .body("total", equalTo(0));
    }

    /** A task narrows both halves too, and one belonging to another project is not found. */
    @Test
    void theTaskNarrowsBothHalvesOfTheListing() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var task = fixtures.createTask(project, alice, "release", null);
        var nightly = fixtures.createTask(project, alice, "nightly", null);
        var inTask = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID(), null, task);
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID(), null, nightly);
        fixtures.createQueuedJob(project, alice, template, "small", task);
        var elsewhere = fixtures.createTask(fixtures.createProject("theirs"), alice, "theirs", null);

        as(alice).queryParam("task", task).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items.type", containsInAnyOrder("job", "pending"))
                .body("items.id", hasItem(inTask.toString()))
                .body("total", equalTo(2));
        as(alice).queryParam("task", task).queryParam("state", "SUCCESS")
                .get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items.id", contains(inTask.toString()))
                .body("total", equalTo(1));
        as(alice).queryParam("task", elsewhere).get("/api/project/{p}/job", project).then()
                .statusCode(404);
    }

    @Test
    void theListingHoldsOnlyThisProject() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.join(alice, other, ProjectRole.VIEWER);
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/job", other).then()
                .statusCode(200)
                .body("items", empty());
    }

    /** Verifies that jobs and queue entries from deleted users remain listable without username. */
    @Test
    void aDeletedRequesterIsListedNameless() {
        var gone = fixtures.createActor("gone");
        fixtures.join(gone, project, ProjectRole.VIEWER);
        fixtures.createJob(project, gone, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createQueuedJob(project, gone, template, "small");
        QuarkusTransaction.requiringNew().run(() -> userService.delete(gone.id()));

        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items.requestedBy.id", everyItem(equalTo(gone.id().toString())))
                .body("items.requestedBy.name", everyItem(nullValue()));
    }

    /** Jobs belonging to other projects return 404. */
    @Test
    void aJobOfAnotherProjectIsNotFound() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var elsewhere = fixtures.createJob(other, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/job/{j}", project, elsewhere).then().statusCode(404);
    }

    /** Viewers can view jobs but do not see createRequest payloads. */
    @Test
    void aViewerReadsOneJobWithoutItsCreateRequest() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(project, alice, small, JobState.RUNNING, UUID.randomUUID(), template);

        as(alice).get("/api/project/{p}/job/{j}", project, job).then()
                .statusCode(200)
                .body("type", equalTo("job"))
                .body("state", equalTo("RUNNING"))
                .body("resourceClass", equalTo("small"))
                .body("createRequest", nullValue());
    }

    @Test
    void aMemberReadsOneJobWithItsCreateRequest() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var job = fixtures.createJob(project, alice, small, JobState.RUNNING, UUID.randomUUID(), template);

        as(alice).get("/api/project/{p}/job/{j}", project, job).then()
                .statusCode(200)
                .body("createRequest.templateId", equalTo(template.toString()))
                .body("createRequest.resourceClass", equalTo("small"))
                .body("createRequest.override", nullValue());
    }

    // ---- templates ----

    /** PROJECT_READ gets the list; the spec inside needs JOB_TEMPLATE_READ on top. */
    @Test
    void aViewerSeesTemplatesWithoutTheirSpec() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).get("/api/project/{p}/job/template", project).then()
                .statusCode(200)
                .body("items.name", contains("build"))
                .body("items[0].spec", nullValue())
                .body("items[0].resourceClass", nullValue());
    }

    @Test
    void aTemplateReaderSeesTheSpec() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.grant(alice, Perm.JOB_TEMPLATE_READ, project);

        as(alice).get("/api/project/{p}/job/template/{t}", project, template).then()
                .statusCode(200)
                .body("name", equalTo("build"))
                .body("spec.image", equalTo("alpine"))
                .body("resourceClass", equalTo("small"));
    }

    /** Global templates are visible to any project, but another project's templates are not. */
    @Test
    void theTemplateListIsThisProjectsAndTheGlobalOnes() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createTemplate("shared", null, small);
        fixtures.createTemplate("theirs-only", other, small);

        as(alice).get("/api/project/{p}/job/template", project).then()
                .statusCode(200)
                .body("items.name", containsInAnyOrder("build", "shared"))
                .body("total", equalTo(2));
    }

    @Test
    void aTemplateOfAnotherProjectIsNotFound() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var elsewhere = fixtures.createTemplate("theirs-only", other, small);

        as(alice).get("/api/project/{p}/job/template/{t}", project, elsewhere)
                .then().statusCode(404);
    }

    @Test
    void aStrangerCannotListTemplates() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/job/template", project)
                .then().statusCode(403);
    }

    /** Verifies that 403 Forbidden responses include standard permission error payload. */
    @Test
    void aViewerCannotCreateAJob() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template))
                .post("/api/project/{p}/job", project).then()
                .statusCode(403)
                .body("message", equalTo("missing permission: job:create"));
    }

    /** An explicit JOB_CREATE grant allows job creation without project membership. */
    @Test
    void anExplicitGrantCreatesWithoutMembership() {
        var alice = fixtures.createActor("alice");
        fixtures.grant(alice, Perm.JOB_CREATE, project);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template))
                .post("/api/project/{p}/job", project).then()
                .statusCode(201)
                .body("type", equalTo("pending"))
                .body("state", equalTo("QUEUED"));
    }

    @Test
    void aMemberCanCreateAJob() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template))
                .post("/api/project/{p}/job", project).then()
                .statusCode(201)
                .body("type", equalTo("pending"))
                .body("state", equalTo("QUEUED"))
                .body("requestedBy.id", equalTo(alice.id().toString()))
                .body("requestedBy.name", equalTo("alice"))
                // Queue entry records the resolved resource class.
                .body("resourceClass", equalTo("small"))
                .body("request.resourceClass", equalTo("small"))
                .body("jobId", nullValue());
    }

    @Test
    void creatingWithoutATemplateIsRejected() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .post("/api/project/{p}/job", project).then()
                .statusCode(400)
                .body("message", equalTo("templateId is required"));
    }

    @Test
    void creatingFromATemplateOfAnotherProjectIsNotFound() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var elsewhere = fixtures.createTemplate("theirs-only", other, small);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", elsewhere))
                .post("/api/project/{p}/job", project).then()
                .statusCode(404)
                .body("message", equalTo("no such template: " + elsewhere));
    }

    // ---- cancelling ----

    @Test
    void aViewerCannotCancel() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(project, alice, small, JobState.RUNNING, null);

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, job).then().statusCode(403);
    }

    @Test
    void aMemberCanCancelARunningJob() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var job = fixtures.createJob(project, alice, small, JobState.RUNNING, null);

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, job).then()
                .statusCode(200)
                .body("type", equalTo("job"))
                .body("state", equalTo("CANCELLED"))
                .body("completedAt", notNullValue());
    }

    @Test
    void cancellingACompletedJobConflicts() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, job).then()
                .statusCode(409)
                .body("message", equalTo("job already SUCCESS: " + job));
    }

    @Test
    void aMemberCanCancelAQueueEntry() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var entry = fixtures.createQueuedJob(project, alice, template, "small");

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, entry).then()
                .statusCode(200)
                .body("type", equalTo("pending"))
                .body("state", equalTo("CANCELLED"))
                // Settled pending jobs have no next attempt scheduled.
                .body("nextAttemptAt", nullValue());
    }

    @Test
    void aViewerCanReadJobLogs() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.createLog(job, "state", "first");
        fixtures.createLog(job, "state", "second");

        as(alice).get("/api/project/{p}/job/{j}/log", project, job).then()
                .statusCode(200)
                .body("items.message", contains("first", "second"))
                // Defaults to maxPageSize when length is not specified.
                .body("length", equalTo(jobConfig.log().maxPageSize()))
                .body("offset", equalTo(0));
    }

    @Test
    void aLogWindowIsCappedAtTheConfiguredMaximum() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var max = jobConfig.log().maxPageSize();

        as(alice).queryParam("length", max + 1).get("/api/project/{p}/job/{j}/log", project, job).then()
                .statusCode(200)
                .body("length", equalTo(max));
    }

    @Test
    void aStrangerCannotReadJobLogs() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(fixtures.createActor("mallory")).get("/api/project/{p}/job/{j}/log", project, job)
                .then().statusCode(403);
    }

    /** Presigning generates a signed URL locally without verifying object existence in storage. */
    @Test
    void aViewerCanPresignAnArtifact() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.createArtifact(job, "out.txt");

        as(alice).get("/api/project/{p}/job/artifact/{a}", project, artifact).then()
                .statusCode(200)
                .body("method", equalTo("GET"))
                .body("url", startsWith("http"));
    }

    @Test
    void anArtifactOfAnotherProjectIsNotFound() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(other, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.createArtifact(job, "out.txt");

        as(alice).get("/api/project/{p}/job/artifact/{a}", project, artifact)
                .then().statusCode(404);
    }

    /** A minimal create-template payload; every test that needs a spec starts from this. */
    private static Map<String, Object> templateBody(String name, String resourceClass) {
        return Map.of("name", name, "resourceClass", resourceClass,
                "spec", Map.of("image", "alpine"));
    }

    @Test
    void anOwnerDefinesATemplate() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        var created = as(alice).contentType(ContentType.JSON).body(templateBody("  deploy  ", "small"))
                .post("/api/project/{p}/job/template", project).then()
                .statusCode(201)
                .body("name", equalTo("deploy"))
                .body("projectId", equalTo(project.toString()))
                .body("resourceClass", equalTo("small"))
                .body("spec.image", equalTo("alpine"))
                .extract().path("id");

        as(alice).get("/api/project/{p}/job/template/{t}", project, created).then()
                .statusCode(200)
                .body("name", equalTo("deploy"));
    }

    @Test
    void aMemberCannotDefineATemplate() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(templateBody("deploy", "small"))
                .post("/api/project/{p}/job/template", project).then().statusCode(403);
    }

    /** Grants permission to create templates independently of user role. */
    @Test
    void theTemplatePermissionIsEnoughOnItsOwn() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.grant(alice, Perm.JOB_TEMPLATE_MANAGE, project);

        as(alice).contentType(ContentType.JSON).body(templateBody("deploy", "small"))
                .post("/api/project/{p}/job/template", project).then().statusCode(201);
    }

    @Test
    void aTemplateNeedsAnImage() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("name", "deploy", "resourceClass", "small", "spec", Map.of()))
                .post("/api/project/{p}/job/template", project).then()
                .statusCode(400)
                .body("message", equalTo("spec.image is required"));
    }

    @Test
    void aTemplateNamingAnUnknownResourceClassIsNotFound() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(templateBody("deploy", "huge"))
                .post("/api/project/{p}/job/template", project).then()
                .statusCode(404)
                .body("message", equalTo("no such resource class: huge"));
    }

    /** Templates cannot mount volumes belonging to other projects. */
    @Test
    void aTemplateCannotMountAnotherProjectsVolume() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var other = fixtures.createProject("theirs");
        var volume = fixtures.createVolume(other, fixtures.createWorker("w1"), "data");

        as(alice).contentType(ContentType.JSON).body(Map.of(
                        "name", "deploy",
                        "resourceClass", "small",
                        "spec", Map.of("image", "alpine",
                                "volumes", Map.of(volume.toString(), Map.of("mountPoint", "/data")))))
                .post("/api/project/{p}/job/template", project).then().statusCode(403);
    }

    @Test
    void anOwnerDeletesATemplate() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).delete("/api/project/{p}/job/template/{t}", project, template).then().statusCode(204);

        as(alice).get("/api/project/{p}/job/template/{t}", project, template).then().statusCode(404);
    }

    /** Global templates cannot be deleted via project-scoped endpoints. */
    @Test
    void aGlobalTemplateCannotBeDeletedThroughAProject() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var global = fixtures.createTemplate("shared", null, small);

        as(alice).delete("/api/project/{p}/job/template/{t}", project, global).then()
                .statusCode(409)
                .body("message", equalTo("a global template is not this project's to delete: " + global));
    }

    @Test
    void aTemplateOfAnotherProjectCannotBeDeletedFromHere() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var other = fixtures.createProject("theirs");
        var theirs = fixtures.createTemplate("build", other, small);

        as(alice).delete("/api/project/{p}/job/template/{t}", project, theirs).then().statusCode(404);
    }

    /**
     * Editing in place rather than delete-and-recreate: the ID is what a task and a re-run payload
     * hold, so a new one would break every reference to the template being edited.
     */
    @Test
    void anOwnerEditsATemplateInPlace() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.createResourceClass("large");

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "  deploy  "))
                .patch("/api/project/{p}/job/template/{t}", project, template).then()
                .statusCode(200)
                .body("id", equalTo(template.toString()))
                .body("name", equalTo("deploy"))
                .body("resourceClass", equalTo("small"))
                .body("spec.image", equalTo("alpine"));

        as(alice).contentType(ContentType.JSON).body(Map.of(
                        "resourceClass", "large",
                        "spec", Map.of("image", "ubuntu:24.04", "command", List.of("bash"))))
                .patch("/api/project/{p}/job/template/{t}", project, template).then()
                .statusCode(200)
                .body("resourceClass", equalTo("large"))
                .body("spec.image", equalTo("ubuntu:24.04"))
                .body("spec.command", contains("bash"));
    }

    @Test
    void anEmptyTemplateEditIsRejectedAndAStrangersIsNotFound() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var other = fixtures.createProject("theirs");
        var theirs = fixtures.createTemplate("build", other, small);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .patch("/api/project/{p}/job/template/{t}", project, template).then()
                .statusCode(400)
                .body("message", equalTo("name, resourceClass or spec is required"));

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "mine now"))
                .patch("/api/project/{p}/job/template/{t}", project, theirs).then().statusCode(404);
    }

    /** Editing a template is the same write as defining one, and costs the same. */
    @Test
    void aMemberCannotEditATemplate() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "deploy"))
                .patch("/api/project/{p}/job/template/{t}", project, template).then().statusCode(403);
    }

    /** A global template is the admin API's; a project may read it but not rewrite it for everyone. */
    @Test
    void aGlobalTemplateCannotBeEditedThroughAProject() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var global = fixtures.createTemplate("shared", null, small);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "mine now"))
                .patch("/api/project/{p}/job/template/{t}", project, global).then()
                .statusCode(409)
                .body("message", equalTo("a global template is not this project's to edit: " + global));
    }

    /**
     * Otherwise the per-project listing is a suggestion: the class has to be refused where it is
     * pinned to the project, on submission and on the templates that submissions start from.
     */
    @Test
    void aClassTheProjectMayNotUseIsRefusedWhereverItIsNamed() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        // Naming a class other than the template's is gated on its own; the grant is the other half.
        fixtures.grant(alice, Perm.JOB_RESOURCE_CLASS, project);
        var huge = fixtures.createResourceClass("huge", false);

        as(alice).contentType(ContentType.JSON).body(templateBody("deploy", "huge"))
                .post("/api/project/{p}/job/template", project).then()
                .statusCode(403)
                .body("message", equalTo("resource class huge is not available to this project"));

        as(alice).contentType(ContentType.JSON).body(Map.of("resourceClass", "huge"))
                .patch("/api/project/{p}/job/template/{t}", project, template).then()
                .statusCode(403);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("templateId", template.toString(), "resourceClass", "huge"))
                .post("/api/project/{p}/job", project).then()
                .statusCode(403)
                .body("message", equalTo("resource class huge is not available to this project"));

        fixtures.allowResourceClass(project, huge);
        as(alice).contentType(ContentType.JSON).body(templateBody("deploy", "huge"))
                .post("/api/project/{p}/job/template", project).then().statusCode(201);
    }

    /**
     * A global template carries its class to every project, so keeping its default is checked too —
     * it would otherwise be the way around the grant.
     */
    @Test
    void aGlobalTemplatesRestrictedClassDoesNotTravelWithIt() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var huge = fixtures.createResourceClass("huge", false);
        var global = fixtures.createTemplate("shared", null, huge);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", global.toString()))
                .post("/api/project/{p}/job", project).then()
                .statusCode(403)
                .body("message", equalTo("resource class huge is not available to this project"));

        fixtures.allowResourceClass(project, huge);
        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", global.toString()))
                .post("/api/project/{p}/job", project).then().statusCode(201);
    }

    @Test
    void anOwnerDeletesAnArtifact() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.createArtifact(job, "out.txt");

        as(alice).delete("/api/project/{p}/job/artifact/{a}", project, artifact).then().statusCode(204);

        as(alice).get("/api/project/{p}/job/artifact/{a}", project, artifact).then().statusCode(404);
        as(alice).get("/api/project/{p}/job/{j}", project, job).then().body("artifacts", empty());
    }

    @Test
    void aMemberCannotDeleteAnArtifact() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.createArtifact(job, "out.txt");

        as(alice).delete("/api/project/{p}/job/artifact/{a}", project, artifact).then().statusCode(403);
        as(alice).get("/api/project/{p}/job/artifact/{a}", project, artifact).then().statusCode(200);
    }

    @Test
    void anArtifactOfAnotherProjectCannotBeDeletedFromHere() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var other = fixtures.createProject("theirs");
        var job = fixtures.createJob(other, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.createArtifact(job, "out.txt");

        as(alice).delete("/api/project/{p}/job/artifact/{a}", project, artifact).then().statusCode(404);
    }

    /** Verifies that write operations on jobs, templates, and artifacts fail when the project is archived. */
    @Test
    void anArchivedProjectRefusesJobAndTemplateWrites() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.createArtifact(job, "out.txt");
        fixtures.archive(project);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template.toString()))
                .post("/api/project/{p}/job", project).then().statusCode(409);
        as(alice).contentType(ContentType.JSON).body(templateBody("deploy", "small"))
                .post("/api/project/{p}/job/template", project).then().statusCode(409);
        as(alice).delete("/api/project/{p}/job/template/{t}", project, template).then().statusCode(409);
        as(alice).delete("/api/project/{p}/job/artifact/{a}", project, artifact).then().statusCode(409);

        // Read operations remain allowed on archived projects.
        as(alice).get("/api/project/{p}/job", project).then().statusCode(200);
        as(alice).get("/api/project/{p}/job/artifact/{a}", project, artifact).then().statusCode(200);
    }

    @Test
    void anAdminReadsAProjectTheyAreNotIn() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        var alice = fixtures.createActor("alice");
        fixtures.createJob(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(admin).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("items.state", contains("SUCCESS"));
    }

    /**
     * {@code createdAt} is when the job was enqueued, so without {@code startedAt} a job that queued
     * for an hour and ran for ten seconds is indistinguishable from one that ran for an hour.
     */
    @Test
    void aPlacedJobSaysWhenItStartedAndWhoRanIt() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var worker = fixtures.createWorker("builder-03");
        var job = fixtures.createJob(project, alice, small, JobState.SUCCESS, worker);

        as(alice).get("/api/project/{p}/job/{j}", project, job).then()
                .statusCode(200)
                .body("worker.id", equalTo(worker.toString()))
                .body("worker.name", equalTo("builder-03"))
                .body("startedAt", notNullValue())
                .body("createdAt", notNullValue());
    }

    /** A queue entry has no host and no start; both are null rather than absent. */
    @Test
    void anUnplacedJobHasNeitherWorkerNorStart() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.createJob(project, alice, small, JobState.CANCELLED, null);

        as(alice).get("/api/project/{p}/job/{j}", project, job).then()
                .statusCode(200)
                .body("worker", nullValue())
                .body("startedAt", nullValue());
    }
}
