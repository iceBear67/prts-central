package io.ib67.prts.user.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.ib67.prts.testing.Fixtures.as;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link UserResource}, which reports the account a request authenticated as.
 */
@QuarkusTest
@Tag("e2e")
class UserResourceE2ETest {

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
        given().get("/api/user").then().statusCode(401);
    }

    @Test
    void aUserSeesTheirOwnAccount() {
        var alice = fixtures.createActor("alice");

        as(alice).get("/api/user").then()
                .statusCode(200)
                .body("id", equalTo(alice.id().toString()))
                .body("name", equalTo("alice"))
                .body("email", notNullValue())
                .body("createdAt", notNullValue())
                .body("subAccountOf", nullValue())
                .body("globalPermissions", empty())
                .body("projects", anEmptyMap());
    }

    @Test
    void grantsAreReportedInTheScopeTheyWereMadeIn() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine");
        fixtures.join(alice, project, ProjectRole.MEMBER);
        fixtures.makeAdmin(alice);
        fixtures.grant(alice, Perm.JOB_CREATE, project);
        fixtures.grant(alice, Perm.JOB_SPEC_IMAGE, project);

        as(alice).get("/api/user").then()
                .statusCode(200)
                .body("globalPermissions", contains(Perm.ADMIN_OF_ALL.permission()))
                // Quoted because a UUID key's dashes are subtraction to GPath.
                .body("projects.'%s'.role".formatted(project), equalTo("MEMBER"))
                .body("projects.'%s'.permissions".formatted(project), containsInAnyOrder(
                        Perm.JOB_CREATE.permission(), Perm.JOB_SPEC_IMAGE.permission()));
    }

    /** A role implies its permissions, so holding one reports the role against an empty grant list. */
    @Test
    void aRoleAloneIsReportedWithoutGrants() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine", alice);

        as(alice).get("/api/user").then()
                .statusCode(200)
                .body("projects.'%s'.role".formatted(project), equalTo("OWNER"))
                .body("projects.'%s'.permissions".formatted(project), empty());
    }

    /** A grant reaches a project the caller is no member of, which reports no role. */
    @Test
    void aGrantWithoutMembershipReportsNoRole() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("theirs");
        fixtures.grant(alice, Perm.JOB_READ, project);

        as(alice).get("/api/user").then()
                .statusCode(200)
                .body("projects.'%s'.role".formatted(project), equalTo("NONE"))
                .body("projects.'%s'.permissions".formatted(project),
                        contains(Perm.JOB_READ.permission()));
    }

    @Test
    void aSubAccountSeesTheProjectItBelongsTo() {
        var alice = fixtures.createActor("alice");
        var project = fixtures.createProject("mine", alice);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(ci).get("/api/user").then()
                .statusCode(200)
                .body("id", equalTo(ci.id().toString()))
                .body("subAccountOf", equalTo(project.toString()));
    }
}
