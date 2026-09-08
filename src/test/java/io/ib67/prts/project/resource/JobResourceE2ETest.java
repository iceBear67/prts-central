package io.ib67.prts.project.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.project.JobConfig;
import io.ib67.prts.project.entity.JobState;
import io.ib67.prts.project.entity.ProjectRole;
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
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

    private UUID project;
    private ResourceClass small;
    private UUID template;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.createProject("mine");
        small = fixtures.createResourceClass("small", null);
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
                .body("$", empty());
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
                .body("$", hasSize(1))
                .body("state", contains("SUCCESS"))
                .body("type", contains("job"));
    }

    /** Pending job queue entries are listed alongside jobs. */
    @Test
    void aQueueEntryIsListedAlongsideJobs() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.createQueuedJob(project, alice, template, "small");

        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("type", contains("pending"))
                .body("state", contains("QUEUED"));
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
                .body("$", empty());
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
                .body("name", contains("build"))
                .body("[0].spec", nullValue())
                .body("[0].resourceClass", nullValue());
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
                .body("name", containsInAnyOrder("build", "shared"));
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

    /**
     * Quarkus ForbiddenException produces an empty 403 response without invoking ClientErrorMapper.
     */
    @Test
    void aViewerCannotCreateAJob() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template))
                .post("/api/project/{p}/job", project).then()
                .statusCode(403)
                .body(emptyString());
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
                .body("requestedBy", equalTo(alice.id().toString()))
                // Queue entry records the resolved resource class.
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

    /** The permission stands in for the role, so it can be handed out on its own. */
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

    /** A template may only mount volumes of its own project. */
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

    /** Global templates are visible here but are the admin API's to manage. */
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

    /** An archived project takes no job, template or artifact write. */
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

        // Reads keep working.
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
                .body("state", contains("SUCCESS"));
    }
}
