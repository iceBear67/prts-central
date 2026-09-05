package io.ib67.prts.user.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.AccessTokenView;
import io.ib67.prts.dto.CreateSubAccountRequest;
import io.ib67.prts.dto.IssuedTokenView;
import io.ib67.prts.dto.SetSubAccountPermissionsRequest;
import io.ib67.prts.dto.SubAccountView;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.SubAccountService;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;
import java.util.UUID;

/**
 * A project's sub-accounts: machine principals it owns outright. Everything here is
 * {@link ProjectRole#OWNER} work, because minting one and setting its permissions is handing out
 * access to the project.
 *
 * <p>The token endpoints are the owner's copy of {@code /user/token}, which refuses a sub-account even
 * over its own token — its key is the owner's to issue and revoke, and only from here.
 */
@Path("/project/{projectId}/subaccount")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(value = Perm.PROJECT_SUBACCOUNT_MANAGE, defaultRole = ProjectRole.OWNER)
public class SubAccountResource {

    @Inject
    SubAccountService subAccountService;
    @Inject
    UserService userService;
    @Inject
    AccessTokenService accessTokenService;
    @Inject
    ProjectService projectService;
    @Inject
    UserContext userContext;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @Transactional
    public SubAccountView createSubAccount(
            @ProjectId @PathParam("projectId") UUID projectId, CreateSubAccountRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        projectService.require(projectId);
        var account = subAccountService.create(
                projectId, request.name().strip(), userContext.require().getId());
        // Brand new, so it holds nothing yet — no need to read its grants back.
        return SubAccountView.of(account, List.of());
    }

    @GET
    @Transactional
    public List<SubAccountView> listSubAccounts(@ProjectId @PathParam("projectId") UUID projectId) {
        projectService.require(projectId);
        return subAccountService.list(projectId).stream()
                .map(account -> view(projectId, account))
                .toList();
    }

    @GET
    @Path("/{userId}")
    @Transactional
    public SubAccountView getSubAccount(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        return view(projectId, subAccountService.require(projectId, userId));
    }

    @DELETE
    @Path("/{userId}")
    public void deleteSubAccount(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        subAccountService.delete(projectId, userId);
    }

    /** Declarative: the body is the complete set, and an empty list takes everything away. */
    @PUT
    @Path("/{userId}/permission")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public SubAccountView setPermissions(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("userId") UUID userId,
            SetSubAccountPermissionsRequest request) {
        if (request == null || request.permissions() == null) {
            throw new BadRequestException("permissions is required, empty to hold none");
        }
        var perms = request.permissions().stream().map(SubAccountResource::perm).distinct().toList();
        subAccountService.setPermissions(projectId, userId, perms);
        // The set just written, not a read-back: the permission cache is invalidated only once this
        // transaction commits, so reading it here would answer with what the sub-account held before.
        // Deduplicated above so it is the set that was stored, not the list as posted.
        return SubAccountView.of(subAccountService.require(projectId, userId), perms);
    }

    @GET
    @Path("/{userId}/token")
    @Transactional
    public AccessTokenView getToken(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        subAccountService.require(projectId, userId);
        return accessTokenService.find(userId)
                .map(AccessTokenView::of)
                .orElseThrow(() -> new NotFoundException("no access token has been issued"));
    }

    /** The plaintext, once. Every call replaces whatever the sub-account was using. */
    @PUT
    @Path("/{userId}/token")
    public IssuedTokenView issueToken(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        subAccountService.require(projectId, userId);
        var issued = accessTokenService.issue(userId);
        return new IssuedTokenView(issued.token(), issued.issuedAt());
    }

    @DELETE
    @Path("/{userId}/token")
    public void revokeToken(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        subAccountService.require(projectId, userId);
        if (!accessTokenService.revoke(userId)) {
            throw new NotFoundException("no access token has been issued");
        }
    }

    /** Scoped to the project in the path, though a sub-account holds nothing outside it by construction. */
    private SubAccountView view(UUID projectId, SubAccount account) {
        return SubAccountView.of(account, userService.permissionsOf(account.getUserId(), projectId));
    }

    private static Perm perm(String permission) {
        return Perm.byPermission(permission)
                .orElseThrow(() -> new BadRequestException("unknown permission: " + permission));
    }
}
