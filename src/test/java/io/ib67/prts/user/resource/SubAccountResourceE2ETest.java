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
 * The permission matrix of {@link SubAccountResource}, whose every route is an owner's.
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
        project = fixtures.project("mine");
    }

    // ---- reaching the resource at all ----

    @Test
    void anUncredentialedRequestIsRejected() {
        given().get("/api/project/{p}/subaccount", project).then().statusCode(401);
    }

    @Test
    void aStrangerCannotListSubAccounts() {
        as(fixtures.actor("mallory")).get("/api/project/{p}/subaccount", project)
                .then().statusCode(403);
    }

    /** The binding sits on the class with {@code defaultRole = OWNER}, so a member is one rung short. */
    @Test
    void aMemberCannotListSubAccounts() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).get("/api/project/{p}/subaccount", project).then().statusCode(403);
    }

    @Test
    void anOwnerCanListSubAccounts() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).get("/api/project/{p}/subaccount", project).then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void aProjectThatDoesNotExistLooksLikeOneIAmNotIn() {
        as(fixtures.actor("mallory")).get("/api/project/{p}/subaccount", UUID.randomUUID())
                .then().statusCode(403);
    }

    /** The interceptor cannot tell absent from forbidden; only an admin gets past it to the 404. */
    @Test
    void onlyAnAdminReachesTheNotFound() {
        var admin = fixtures.actor("root");
        fixtures.makeAdmin(admin);

        as(admin).get("/api/project/{p}/subaccount", UUID.randomUUID()).then()
                .statusCode(404)
                // NotFoundMapper answers projectService.require's NoSuchElementException without a body.
                .body(emptyString());
    }

    // ---- creating ----

    @Test
    void anOwnerCanCreateASubAccount() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "  ci  "))
                .post("/api/project/{p}/subaccount", project).then()
                // @ResponseStatus(CREATED).
                .statusCode(201)
                .body("userId", notNullValue())
                // The resource strips before storing.
                .body("name", equalTo("ci"))
                // A fresh sub-account holds nothing until setPermissions says otherwise.
                .body("permissions", empty())
                .body("createdBy", equalTo(alice.id().toString()));
    }

    @Test
    void aMemberCannotCreateASubAccount() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.MEMBER);

        as(alice).contentType(ContentType.JSON).body(Map.of("name", "ci"))
                .post("/api/project/{p}/subaccount", project).then().statusCode(403);
    }

    /** PROJECT_SUBACCOUNT_MANAGE granted outright stands in for the owner role. */
    @Test
    void anExplicitGrantManagesWithoutTheOwnerRole() {
        var alice = fixtures.actor("alice");
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
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .post("/api/project/{p}/subaccount", project).then()
                .statusCode(400)
                .body("message", equalTo("name is required"));
    }

    // ---- reading one ----

    @Test
    void theListHoldsOnlyThisProjectsSubAccounts() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(alice, other, ProjectRole.OWNER);
        fixtures.subAccount(project, "mine-ci", alice);
        fixtures.subAccount(other, "theirs-ci", alice);

        as(alice).get("/api/project/{p}/subaccount", project).then()
                .statusCode(200)
                .body("name", contains("mine-ci"));
    }

    @Test
    void anOwnerCanReadOneSubAccount() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then()
                .statusCode(200)
                .body("name", equalTo("ci"));
    }

    /**
     * {@code getSubAccount} scopes its lookup by project, so another project's sub-account is absent
     * rather than someone else's. The message comes from a {@code jakarta.ws.rs.NotFoundException},
     * which {@code ClientErrorMapper} does give a body.
     */
    @Test
    void aSubAccountOfAnotherProjectIsNotFoundHere() {
        var alice = fixtures.actor("alice");
        var other = fixtures.project("theirs");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(alice, other, ProjectRole.OWNER);
        var ci = fixtures.subAccount(other, "theirs-ci", alice);

        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then()
                .statusCode(404)
                .body("message",
                        equalTo("no such sub-account in project " + project + ": " + ci.id()));
    }

    // ---- permissions ----

    @Test
    void anOwnerCanSetPermissions() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("job:read", "job:create")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(200)
                // SubAccountView sorts the identifiers, so the order is the alphabet's, not the request's.
                .body("permissions", contains("job:create", "job:read"));
    }

    /** The route replaces the grants rather than adding to them. */
    @Test
    void settingPermissionsDropsTheOnesLeftOut() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);
        setPermissions(alice, ci, "job:read", "job:create");

        setPermissions(alice, ci, "job:read");

        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then()
                .statusCode(200)
                .body("permissions", contains("job:read"));
    }

    /** A sub-account minting further sub-accounts would let a project's rights escape it. */
    @Test
    void aSubAccountMayNotHoldSubAccountManagement() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("project:subaccount:manage")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("a sub-account may not hold project:subaccount:manage"));
    }

    /** ADMIN_OF_ALL is global, so no project owner is in a position to hand it out. */
    @Test
    void aGlobalPermissionIsNotAProjectsToGrant() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("admin:all")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("not a project's to grant: admin:all"));
    }

    @Test
    void anUnknownPermissionIsRejected() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("job:fly")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("unknown permission: job:fly"));
    }

    /** An absent list is a mistake; an empty one is a deliberate revocation, and the message says so. */
    @Test
    void anAbsentPermissionListIsRejected() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).contentType(ContentType.JSON).body(Map.of())
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(400)
                .body("message", equalTo("permissions is required, empty to hold none"));
    }

    @Test
    void aMemberCannotSetPermissions() {
        var alice = fixtures.actor("alice");
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(bob).contentType(ContentType.JSON)
                .body(Map.of("permissions", List.of("job:read")))
                .put("/api/project/{p}/subaccount/{u}/permission", project, ci.id()).then()
                .statusCode(403);
    }

    // ---- tokens ----

    /**
     * {@code Fixtures.subAccount} issues one, so this uses a sub-account created over the wire to
     * observe the state before any token exists.
     */
    @Test
    void aSubAccountHasNoTokenUntilOneIsIssued() {
        var alice = fixtures.actor("alice");
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
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        var token = as(alice).put("/api/project/{p}/subaccount/{u}/token", project, ci.id()).then()
                .statusCode(200)
                .body("token", startsWith("prts_"))
                .body("issuedAt", notNullValue())
                .extract().path("token").toString();

        // The plaintext is handed out once and must authenticate; a sub-account is in no project of its own.
        given().header("Authorization", "Bearer " + token)
                .get("/api/project").then().statusCode(200).body("$", empty());
    }

    /** Issuing again replaces the hash, so whatever was handed out before stops working. */
    @Test
    void issuingAgainRetiresTheOldToken() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).put("/api/project/{p}/subaccount/{u}/token", project, ci.id())
                .then().statusCode(200);

        as(ci).get("/api/project").then().statusCode(401);
    }

    @Test
    void afterIssuingTheOwnerSeesOnlyTheTimestamp() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(alice).get("/api/project/{p}/subaccount/{u}/token", project, ci.id()).then()
                .statusCode(200)
                // AccessTokenView carries no plaintext; only the issuing call ever returns one.
                .body("issuedAt", notNullValue())
                .body("token", nullValue());
    }

    @Test
    void aMemberCannotIssueASubAccountsToken() {
        var alice = fixtures.actor("alice");
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(bob).put("/api/project/{p}/subaccount/{u}/token", project, ci.id())
                .then().statusCode(403);
    }

    // ---- deleting ----

    @Test
    void anOwnerCanDeleteASubAccount() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var ci = fixtures.subAccount(project, "ci", alice);

        // The endpoint returns void, so RESTEasy answers 204.
        as(alice).delete("/api/project/{p}/subaccount/{u}", project, ci.id())
                .then().statusCode(204);
        as(alice).get("/api/project/{p}/subaccount", project).then()
                .statusCode(200)
                .body("$", empty());
        as(alice).get("/api/project/{p}/subaccount/{u}", project, ci.id()).then().statusCode(404);
        // The user row goes with it, so the token it held no longer resolves to anyone.
        as(ci).get("/api/project").then().statusCode(401);
    }

    @Test
    void deletingASubAccountThatDoesNotExistIsNotFound() {
        var alice = fixtures.actor("alice");
        fixtures.join(alice, project, ProjectRole.OWNER);
        var stranger = UUID.randomUUID();

        as(alice).delete("/api/project/{p}/subaccount/{u}", project, stranger).then()
                .statusCode(404)
                .body("message",
                        equalTo("no such sub-account in project " + project + ": " + stranger));
    }

    @Test
    void aMemberCannotDeleteASubAccount() {
        var alice = fixtures.actor("alice");
        var bob = fixtures.actor("bob");
        fixtures.join(alice, project, ProjectRole.OWNER);
        fixtures.join(bob, project, ProjectRole.MEMBER);
        var ci = fixtures.subAccount(project, "ci", alice);

        as(bob).delete("/api/project/{p}/subaccount/{u}", project, ci.id()).then().statusCode(403);
    }

    @Test
    void anAdminManagesAProjectTheyAreNotIn() {
        var admin = fixtures.actor("root");
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
