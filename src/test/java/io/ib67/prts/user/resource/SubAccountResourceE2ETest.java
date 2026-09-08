package io.ib67.prts.user.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.testing.DatabaseCleaner;
import io.ib67.prts.testing.Fixtures;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Permission and lifecycle tests for {@link SubAccountResource}.
 */
@QuarkusTest
@Tag("e2e")
class SubAccountResourceE2ETest {

    @Inject
    Fixtures fixtures;
    @Inject
    DatabaseCleaner databaseCleaner;

    private UUID project;

    @BeforeEach
    void reset() {
        databaseCleaner.clean();
        project = fixtures.createProject("mine");
    }

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/project/{p}/subaccount", project).then().statusCode(401);
    }

    @Test
    void aStrangerCannotListSubAccounts() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/subaccount", project)
                .then().statusCode(403);
    }

    /** Access requires OWNER role; project members cannot list sub-accounts. */
    @Test
    void aMemberCannotListSubAccounts() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).get("/api/project/{p}/subaccount", project).then().statusCode(403);
    }

    @Test
    void anOwnerCanListSubAccounts() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).get("/api/project/{p}/subaccount", project).then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(fixtures.createActor("mallory")).get("/api/project/{p}/subaccount", UUID.randomUUID())
                .then().statusCode(403);
    }

    /** Non-members receive 403 on non-existent projects, while admins receive 404. */
    @Test
    void onlyAnAdminReachesTheNotFound() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).get("/api/project/{p}/subaccount", UUID.randomUUID()).then()
                .statusCode(404)
                .body(emptyString());
    }

    @Test
    void anOwnerCanCreateASubAccount() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "  ci  "))
                .post("/api/project/{p}/subaccount", project).then()
                .statusCode(201)
                .body("userId", notNullValue())
                // Name is trimmed before storing.
                .body("name", equalTo("ci"))
                // Newly created sub-accounts have no permissions granted by default.
                .body("permissions", empty())
                .body("createdBy", equalTo(alice.id().toString()));
    }

    @Test
    void aMemberCannotCreateASubAccount() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "ci"))
                .post("/api/project/{p}/subaccount", project).then().statusCode(403);
    }

    /** Explicit PROJECT_SUBACCOUNT_MANAGE grant allows managing sub-accounts without the OWNER role. */
    @Test
    void anExplicitGrantManagesWithoutTheOwnerRole() {
        var alice = fixtures.createActor("alice");
        fixtures.grant(alice, Perm.PROJECT_SUBACCOUNT_MANAGE, project);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "ci"))
                .post("/api/project/{p}/subaccount", project).then()
                .statusCode(201)
                .body("name", equalTo("ci"))
                .body("userId", notNullValue())
                .body("permissions", empty())
                .body("createdBy", equalTo(alice.id().toString()));
    }

    @Test
    void aNamelessSubAccountIsRejected() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .post("/api/project/{p}/subaccount", project).then()
                .statusCode(400)
                .body("message", equalTo("name is required"));
    }

    @Test
    void theListHoldsOnlyThisProjectsSubAccounts() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(alice, other, ProjectRole.OWNER);
        fixtures.createSubAccount(project, "mine-ci", alice);
        fixtures.createSubAccount(other, "theirs-ci", alice);

        as(alice).get("/api/project/{p}/subaccount", project).then()
                .statusCode(200)
                .body("name", contains("mine-ci"));
    }

    @Test
    void anOwnerCanReadOneSubAccount() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then()
                .statusCode(200)
                .body("name", equalTo("ci"));
    }

    /** Sub-accounts are scoped to their project; looking up another project's sub-account returns 404. */
    @Test
    void aSubAccountOfAnotherProjectIsNotFoundHere() {
        var alice = fixtures.createActor("alice");
        var other = fixtures.createProject("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(alice, other, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(other, "theirs-ci", alice);

        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then()
                .statusCode(404)
                .body("message",
                        equalTo("no such sub-account in project " + project + ": " + ci.id()));
    }

    // ---- permissions ----

    @Test
    void anOwnerCanSetPermissions() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("job:read", "job:create")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(200)
                // SubAccountView sorts permissions alphabetically.
                .body("permissions", contains("job:create", "job:read"));
    }

    /** Setting permissions replaces all existing grants. */
    @Test
    void settingPermissionsDropsTheOnesLeftOut() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);
        setPermissions(alice, ci, "job:read", "job:create");

        setPermissions(alice, ci, "job:read");

        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then()
                .statusCode(200)
                .body("permissions", contains("job:read"));
    }

    /** Sub-accounts cannot be granted sub-account management permissions. */
    @Test
    void aSubAccountMayNotHoldSubAccountManagement() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("project:subaccount:manage")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("a sub-account may not hold project:subaccount:manage"));
    }

    /** Global permissions like admin:all cannot be granted at the project level. */
    @Test
    void aGlobalPermissionIsNotAProjectsToGrant() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("admin:all")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("not a project's to grant: admin:all"));
    }

    @Test
    void anUnknownPermissionIsRejected() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("job:fly")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("unknown permission: job:fly"));
    }

    /** Omitting the permissions array is rejected, while an empty array clears all permissions. */
    @Test
    void anAbsentPermissionListIsRejected() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("permissions is required, empty to hold none"));
    }

    @Test
    void aMemberCannotSetPermissions() {
        var alice = fixtures.createActor("alice");
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(bob).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("job:read")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(403);
    }

    /** Verifies that a newly created sub-account has no access token until one is explicitly generated. */
    @Test
    void aSubAccountHasNoTokenUntilOneIsIssued() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = as(alice).contentType(ContentType.JSON).body(Map.of("name", "ci"))
                .post("/api/project/{p}/subaccount", project)
                .then().statusCode(201).extract().path("userId").toString();

        as(alice).get("/api/project/{p}/subaccount/{u}/token", project, ci).then()
                .statusCode(404)
                .body("message", equalTo("no access token has been issued"));
    }

    @Test
    void anOwnerCanIssueASubAccountsToken() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        var token = as(alice).put("/api/project/{p}/subaccount/{u}/token", project, ci.id()).then()
                .statusCode(200)
                .body("token", startsWith("prts_"))
                .body("issuedAt", notNullValue())
                .extract().path("token").toString();

        // The returned plaintext token can authenticate immediately.
        given().header("Authorization", "Bearer " + token)
                .get("/api/project").then().statusCode(200).body("$", empty());
    }

    /** Regenerating a token invalidates the previously issued token. */
    @Test
    void issuingAgainRetiresTheOldToken() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).put("/api/project/{p}/subaccount/{u}/token", project, ci.id())
                .then().statusCode(200);

        as(ci).get("/api/project").then().statusCode(401);
    }

    @Test
    void afterIssuingTheOwnerSeesOnlyTheTimestamp() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).get("/api/project/{p}/subaccount/{u}/token", project, ci.id()).then()
                .statusCode(200)
                // AccessTokenView excludes plaintext token values.
                .body("issuedAt", notNullValue())
                .body("token", nullValue());
    }

    @Test
    void aMemberCannotIssueASubAccountsToken() {
        var alice = fixtures.createActor("alice");
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(bob).put("/api/project/{p}/subaccount/{u}/token", project, ci.id())
                .then().statusCode(403);
    }

    @Test
    void anOwnerCanDeleteASubAccount() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(alice).delete("/api/project/{p}/subaccount/{u}", project, ci.id())
                .then().statusCode(204);
        as(alice).get("/api/project/{p}/subaccount", project).then()
                .statusCode(200)
                .body("$", empty());
        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then().statusCode(404);
        // Deleting the sub-account also deletes its user record and invalidates its token.
        as(ci).get("/api/project").then().statusCode(401);
    }

    @Test
    void deletingASubAccountThatDoesNotExistIsNotFound() {
        var alice = fixtures.createActor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var stranger = UUID.randomUUID();

        as(alice).delete("/api/project/{p}/subaccount/{u}", project, stranger).then()
                .statusCode(404)
                .body("message",
                        equalTo("no such sub-account in project " + project + ": " + stranger));
    }

    @Test
    void aMemberCannotDeleteASubAccount() {
        var alice = fixtures.createActor("alice");
        var bob = fixtures.createActor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);
        var ci = fixtures.createSubAccount(project, "ci", alice);

        as(bob).delete("/api/project/{p}/subaccount/{u}", project, ci.id()).then().statusCode(403);
    }

    @Test
    void anAdminManagesAProjectTheyAreNotIn() {
        var admin = fixtures.createActor("root");
        fixtures.makeAdmin(admin);

        as(admin).contentType(ContentType.JSON).body(Map.of("name", "ci"))
                .post("/api/project/{p}/subaccount", project).then()
                .statusCode(201)
                .body("createdBy", equalTo(admin.id().toString()));
    }

    private void setPermissions(Fixtures.Actor owner, Fixtures.Actor subAccount, String... permissions) {
        as(owner).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of(permissions)))
                .put("/api/project/{p}/subaccount/{u}/permission", project, subAccount.id())
                .then().statusCode(200);
    }
}
