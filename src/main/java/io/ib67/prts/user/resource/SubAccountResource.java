package io.ib67.prts.user.resource;

import io.ib67.prts.Perm;
import io.ib67.prts.auth.ProjectId;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.AccessTokenView;
import io.ib67.prts.dto.request.CreateSubAccountRequest;
import io.ib67.prts.dto.IssuedTokenView;
import io.ib67.prts.dto.request.SetPermissionsRequest;
import io.ib67.prts.dto.project.SubAccountView;
import io.ib67.prts.project.entity.ProjectRole;
import io.ib67.prts.project.ProjectService;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.SubAccountService;
import io.ib67.prts.user.UserContext;
import io.ib67.prts.user.UserService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
 * REST endpoint managing project sub-accounts, permissions, and access tokens.
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
            @ProjectId @PathParam("projectId") UUID projectId,
            @NotNull(message = "a request body is required") @Valid CreateSubAccountRequest request) {
        projectService.requireWritable(projectId);
        var account = subAccountService.create(
                projectId, request.name(), userContext.require().getId());
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

    private SubAccountView view(UUID projectId, SubAccount account) {
        return SubAccountView.of(account, userService.permissionsOf(account.getUserId(), projectId));
    }
}
