package io.ib67.prts.user;

import io.ib67.prts.Perm;
import io.ib67.prts.agent.worker.entity.ResourceClass;
import io.ib67.prts.job.entity.JobState;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.ib67.prts.testing.Fixtures.as;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies that permissions listed in {@code permission.banned} are globally disabled across all endpoints.
 *
 * <p>Runs under a dedicated test profile because permission bans are loaded from configuration at startup.
 */
@QuarkusTest
@TestProfile(BannedPermissionE2ETest.JobCreateBanned.class)
@Tag("e2e")
class BannedPermissionE2ETest {

    public static class JobCreateBanned implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("permission.banned", Perm.JOB_CREATE.permission());
        }
    }

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private UUID project;
    private UUID template;
    private ResourceClass small;
    private Fixtures.Actor alice;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        alice = fixtures.createActor("alice");
        project = fixtures.createProject("mine", alice);
        small = fixtures.createResourceClass("small", null);
        template = fixtures.createTemplate("build", project, small);
    }

    /** Verifies that project roles do not grant access when the required permission is globally banned. */
    @Test
    void theRoleThatStandsInForItNoLongerDoes() {
        as(alice).contentType(ContentType.JSON).body(Map.of("templateId", template.toString()))
                .post("/api/project/{p}/job", project).then()
                .statusCode(403)
                .body("message", equalTo(
                        "permission is globally disabled: " + Perm.JOB_CREATE.permission()));
    }

    @Test
    void anExplicitGrantNoLongerDoesEither() {
        var bob = fixtures.createActor("bob");
        fixtures.grant(bob, Perm.JOB_CREATE, project);
        fixtures.grant(bob, Perm.PROJECT_READ, project);

        as(bob).contentType(ContentType.JSON).body(Map.of("templateId", template.toString()))
                .post("/api/project/{p}/job", project).then().statusCode(403);
    }

    /** Verifies that globally banned permissions apply to administrators as well. */
    @Test
    void anAdminIsStoppedAsWell() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).contentType(ContentType.JSON).body(Map.of("templateId", template.toString()))
                .post("/api/project/{p}/job", project).then().statusCode(403);
    }

    /** Verifies that other permissions and endpoints remain unaffected by the ban. */
    @Test
    void nothingElseIsAffected() {
        as(alice).get("/api/project/{p}", project).then().statusCode(200);
        as(alice).get("/api/project/{p}/job", project).then().statusCode(200);
        as(alice).contentType(ContentType.JSON).body(Map.of("name", "renamed"))
                .patch("/api/project/{p}", project).then().statusCode(200);
    }

    /** Verifies that views and permission catalogues reflect banned permissions. */
    @Test
    void theCatalogueAndTheViewsBothShowItGone() {
        var job = fixtures.createJob(
                project, alice, small, JobState.SUCCESS, UUID.randomUUID(), template);

        as(alice).get("/api/project/{p}/job/{j}", project, job).then()
                .statusCode(200)
                .body("createRequest", nullValue());

        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);
        as(admin).get("/api/admin/permission").then()
                .statusCode(200)
                .body("findAll { it.banned == true }.permission", contains(Perm.JOB_CREATE.permission()));
    }
}
