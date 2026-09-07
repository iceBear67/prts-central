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
 * The permission matrix of {@link JobResource}, over the real authentication chain.
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
        project = fixtures.project("mine");
        small = fixtures.resourceClass("small", null);
        template = fixtures.template("build", project, small);
    }

    // ---- reading ----

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/project/{p}/job", project).then().statusCode(401);
    }

    @Test
    void aStrangerCannotListJobs() {
        as(fixtures.actor("mallory")).get("/api/project/{p}/job", project).then().statusCode(403);
    }

    /** JOB_READ defaults to VIEWER, so a stranger is refused before the project is ever looked up. */
    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(fixtures.actor("mallory")).get("/api/project/{p}/job", UUID.randomUUID())
                .then().statusCode(403);
    }

    /**
     * Past the permission check the non-disclosure rule no longer applies, and both listings have to say
     * so: neither queries a project row of its own, so an absent project would otherwise read as 200 —
     * empty for jobs, and the global templates for templates.
     */
    @Test
    void anAdminIsToldWhenTheProjectDoesNotExist() {
        var root = fixtures.actor("root");
        fixtures.makeAdmin(root);
        var absent = UUID.randomUUID();

        as(root).get("/api/project/{p}/job", absent).then().statusCode(404);
        as(root).get("/api/project/{p}/job/template", absent).then().statusCode(404);
    }

    @Test
    void aViewerCanListJobs() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("$", empty());
    }

    /** A job still PENDING with no worker is not visible; Job.VISIBLE_ROW is what hides it. */
    @Test
    void anUnplacedJobIsNotListed() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.job(project, alice, small, JobState.PENDING, null);
        fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("state", contains("SUCCESS"))
                .body("type", contains("job"));
    }

    /** The listing merges queue entries with jobs; an entry that has no job yet reads as itself. */
    @Test
    void aQueueEntryIsListedAlongsideJobs() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.queued(project, alice, template, "small");

        as(alice).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("type", contains("pending"))
                .body("state", contains("QUEUED"));
    }

    @Test
    void theListingHoldsOnlyThisProject() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.join(alice, other, ProjectRole.VIEWER);
        fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/job", other).then()
                .statusCode(200)
                .body("$", empty());
    }

    /** A job of another project must not be readable through this project's path. */
    @Test
    void aJobOfAnotherProjectIsNotFound() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var elsewhere = fixtures.job(other, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).get("/api/project/{p}/job/{j}", project, elsewhere).then().statusCode(404);
    }

    /** JOB_CREATE is a MEMBER's, so a viewer is not handed the payload to re-run the job. */
    @Test
    void aViewerReadsOneJobWithoutItsCreateRequest() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.job(project, alice, small, JobState.RUNNING, UUID.randomUUID(), template);

        as(alice).get("/api/project/{p}/job/{j}", project, job).then()
                .statusCode(200)
                .body("type", equalTo("job"))
                .body("state", equalTo("RUNNING"))
                .body("resourceClass", equalTo("small"))
                .body("createRequest", nullValue());
    }

    @Test
    void aMemberReadsOneJobWithItsCreateRequest() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var job = fixtures.job(project, alice, small, JobState.RUNNING, UUID.randomUUID(), template);

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
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).get("/api/project/{p}/job/template", project).then()
                .statusCode(200)
                .body("name", contains("build"))
                .body("[0].spec", nullValue())
                .body("[0].resourceClass", nullValue());
    }

    @Test
    void aTemplateReaderSeesTheSpec() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.grant(alice, Perm.JOB_TEMPLATE_READ, project);

        as(alice).get("/api/project/{p}/job/template/{t}", project, template).then()
                .statusCode(200)
                .body("name", equalTo("build"))
                .body("spec.image", equalTo("alpine"))
                .body("resourceClass", equalTo("small"));
    }

    /** Global templates are visible everywhere; another project's are visible nowhere else. */
    @Test
    void theTemplateListIsThisProjectsAndTheGlobalOnes() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        fixtures.template("shared", null, small);
        fixtures.template("theirs-only", other, small);

        // listVisibleFetched has no order by, so only the membership of the list is asserted.
        as(alice).get("/api/project/{p}/job/template", project).then()
                .statusCode(200)
                .body("name", containsInAnyOrder("build", "shared"));
    }

    @Test
    void aTemplateOfAnotherProjectIsNotFound() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var elsewhere = fixtures.template("theirs-only", other, small);

        as(alice).get("/api/project/{p}/job/template/{t}", project, elsewhere)
                .then().statusCode(404);
    }

    @Test
    void aStrangerCannotListTemplates() {
        as(fixtures.actor("mallory")).get("/api/project/{p}/job/template", project)
                .then().statusCode(403);
    }

    // ---- creating ----

    /**
     * The 403 carries no body: {@code io.quarkus.security.ForbiddenException} is no
     * {@code WebApplicationException}, so Quarkus' own mapper answers and never sees
     * {@code ClientErrorMapper}. A denial says nothing about what was missing.
     */
    @Test
    void aViewerCannotCreateAJob() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template))
                .post("/api/project/{p}/job", project).then()
                .statusCode(403)
                .body(emptyString());
    }

    /** JOB_CREATE granted outright is the sub-account's route in, without any membership role. */
    @Test
    void anExplicitGrantCreatesWithoutMembership() {
        var alice = fixtures.actor("alice");
        fixtures.grant(alice, Perm.JOB_CREATE, project);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template))
                .post("/api/project/{p}/job", project).then()
                .statusCode(201)
                .body("type", equalTo("pending"))
                .body("state", equalTo("QUEUED"));
    }

    @Test
    void aMemberCanCreateAJob() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template))
                .post("/api/project/{p}/job", project).then()
                .statusCode(201)
                .body("type", equalTo("pending"))
                .body("state", equalTo("QUEUED"))
                .body("requestedBy", equalTo(alice.id().toString()))
                // The queue entry pins the class the launcher resolved, not the one asked for.
                .body("request.resourceClass", equalTo("small"))
                .body("jobId", nullValue());
    }

    @Test
    void creatingWithoutATemplateIsRejected() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .post("/api/project/{p}/job", project).then()
                .statusCode(400)
                .body("message", equalTo("templateId is required"));
    }

    @Test
    void creatingFromATemplateOfAnotherProjectIsNotFound() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var elsewhere = fixtures.template("theirs-only", other, small);

        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", elsewhere))
                .post("/api/project/{p}/job", project).then()
                .statusCode(404)
                .body("message", equalTo("no such template: " + elsewhere));
    }

    // ---- cancelling ----

    @Test
    void aViewerCannotCancel() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.job(project, alice, small, JobState.RUNNING, null);

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, job).then().statusCode(403);
    }

    @Test
    void aMemberCanCancelARunningJob() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var job = fixtures.job(project, alice, small, JobState.RUNNING, null);

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, job).then()
                .statusCode(200)
                .body("type", equalTo("job"))
                .body("state", equalTo("CANCELLED"))
                .body("completedAt", notNullValue());
    }

    @Test
    void cancellingACompletedJobConflicts() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var job = fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, job).then()
                .statusCode(409)
                .body("message", equalTo("job already SUCCESS: " + job));
    }

    @Test
    void aMemberCanCancelAQueueEntry() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        var entry = fixtures.queued(project, alice, template, "small");

        as(alice).post("/api/project/{p}/job/{j}/cancel", project, entry).then()
                .statusCode(200)
                .body("type", equalTo("pending"))
                .body("state", equalTo("CANCELLED"))
                // PendingJobView blanks nextAttemptAt once the state is settled.
                .body("nextAttemptAt", nullValue());
    }

    // ---- logs and artifacts ----

    @Test
    void aViewerCanReadJobLogs() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        fixtures.log(job, "state", "first");
        fixtures.log(job, "state", "second");

        as(alice).get("/api/project/{p}/job/{j}/log", project, job).then()
                .statusCode(200)
                .body("items.message", contains("first", "second"))
                // No length asked for, so the window is the maximum.
                .body("length", equalTo(jobConfig.log().maxPageSize()))
                .body("offset", equalTo(0));
    }

    @Test
    void aLogWindowIsCappedAtTheConfiguredMaximum() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var max = jobConfig.log().maxPageSize();

        as(alice).queryParam("length", max + 1).get("/api/project/{p}/job/{j}/log", project, job).then()
                .statusCode(200)
                .body("length", equalTo(max));
    }

    @Test
    void aStrangerCannotReadJobLogs() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(fixtures.actor("mallory")).get("/api/project/{p}/job/{j}/log", project, job)
                .then().statusCode(403);
    }

    /** Presigning is local crypto, so this asserts the URL was minted, not that the object exists. */
    @Test
    void aViewerCanPresignAnArtifact() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.artifact(job, "out.txt");

        as(alice).get("/api/project/{p}/job/artifact/{a}", project, artifact).then()
                .statusCode(200)
                .body("method", equalTo("GET"))
                .body("url", startsWith("http"));
    }

    @Test
    void anArtifactOfAnotherProjectIsNotFound() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.VIEWER);
        var job = fixtures.job(other, alice, small, JobState.SUCCESS, UUID.randomUUID());
        var artifact = fixtures.artifact(job, "out.txt");

        as(alice).get("/api/project/{p}/job/artifact/{a}", project, artifact)
                .then().statusCode(404);
    }

    // ---- admin ----

    @Test
    void anAdminReadsAProjectTheyAreNotIn() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);
        var alice = fixtures.actor("alice");
        fixtures.job(project, alice, small, JobState.SUCCESS, UUID.randomUUID());

        as(admin).get("/api/project/{p}/job", project).then()
                .statusCode(200)
                .body("state", contains("SUCCESS"));
    }
}
