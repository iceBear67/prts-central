package io.ib67.prts.user.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.AccessTokenView;
import io.ib67.prts.dto.Page;
import io.ib67.prts.dto.request.CreateSubAccountRequest;
import io.ib67.prts.dto.IssuedTokenView;
import io.ib67.prts.dto.request.SetPermissionsRequest;
import io.ib67.prts.dto.project.SubAccountView;
import io.ib67.prts.job.entity.ProjectRole;
import io.ib67.prts.project.ProjectConfig;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.SubAccountService;
import io.ib67.prts.user.UserContext;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.UUID;

/**
 * REST endpoint managing project sub-accounts, permissions, and access tokens.
 */
@Path("/project/{projectId}/subaccount")
@Produces(MediaType.APPLICATION_JSON)
@RequirePermission(value = Perm.PROJECT_SUBACCOUNT_MANAGE, defaultRole = ProjectRole.OWNER)
public class SubAccountResource {

    @Inject
    SubAccountService subAccountService;
    @Inject
    ProjectConfig projectConfig;
    @Inject
    AccessTokenService accessTokenService;
    @Inject
    ProjectService projectService;
    @Inject
    UserContext userContext;

    /**
     * Opens a sub-account, optionally with its grants and its credential.
     *
     * <p>All three land in one transaction, so a refused permission name or a failed mint leaves no
     * half-made account behind. The token appears only here; nothing reads it back afterwards.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ResponseStatus(RestResponse.StatusCode.CREATED)
    @Transactional
    public SubAccountView createSubAccount(
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid CreateSubAccountRequest request) {
        projectService.requireWritable(projectId);
        // Resolved before the first write so an unknown permission name costs nothing.
        var permissions = request.resolvedPermissions();
        var account = subAccountService.create(
                projectId, request.name(), userContext.require().getId());
        if (!permissions.isEmpty()) {
            subAccountService.setPermissions(projectId, account.getUserId(), permissions);
        }
        var issued = request.issueToken() ? accessTokenService.issue(account.getUserId()) : null;
        // Pass the grants directly: the permission cache is invalidated only once this commits.
        return subAccountService.viewOf(account, permissions, issued == null
                ? null
                : new IssuedTokenView(issued.token(), issued.issuedAt()));
    }

    @GET
    @Transactional
    public Page<SubAccountView> listSubAccounts(
            @ProjectId @PathParam("projectId") UUID projectId,
            @QueryParam("query") @Nullable String query,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        projectService.require(projectId);
        var window = Pages.clampLength(length, projectConfig.list().maxPageSize());
        var start = Pages.clampOffset(offset, window);
        return new Page<>(
                subAccountService.viewOf(
                        projectId, subAccountService.search(projectId, query, start, window)),
                start,
                window,
                SubAccount.countByProject(projectId, query));
    }

    @GET
    @Path("/{userId}")
    @Transactional
    public SubAccountView getSubAccount(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        return subAccountService.viewOf(projectId, subAccountService.require(projectId, userId));
    }

    @DELETE
    @Path("/{userId}")
    public void deleteSubAccount(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        projectService.requireWritable(projectId);
        subAccountService.delete(projectId, userId);
    }

    /** Sets the permissions for a sub-account, replacing existing grants. */
    @PUT
    @Path("/{userId}/permission")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public SubAccountView setPermissions(
            @ProjectId @PathParam("projectId") UUID projectId,
            @PathParam("userId") UUID userId,
            @NotNull(message = "a request body is required") @Valid SetPermissionsRequest request) {
        var perms = request.resolved();
        projectService.requireWritable(projectId);
        subAccountService.setPermissions(projectId, userId, perms);
        // Pass updated permissions directly to bypass uncommitted permission cache.
        return subAccountService.viewOf(subAccountService.require(projectId, userId), perms, null);
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

    /** Issues or regenerates an access token for the sub-account. */
    @PUT
    @Path("/{userId}/token")
    public IssuedTokenView issueToken(
            @ProjectId @PathParam("projectId") UUID projectId, @PathParam("userId") UUID userId) {
        projectService.requireWritable(projectId);
        subAccountService.require(projectId, userId);
        var issued = accessTokenService.issue(userId);
        return new IssuedTokenView(issued.token(), issued.issuedAt());
    }
}
