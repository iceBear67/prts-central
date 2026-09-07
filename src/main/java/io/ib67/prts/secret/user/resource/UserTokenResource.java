package io.ib67.prts.secret.user.resource;

import io.ib67.prts.dto.AccessTokenView;
import io.ib67.prts.dto.IssuedTokenView;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.UserContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * REST endpoint for managing the current user's personal access token.
 *
 * <p>Note: Sub-account tokens are managed separately through {@code SubAccountResource}.
 */
@Path("/user/token")
@Produces(MediaType.APPLICATION_JSON)
public class UserTokenResource {

    @Inject
    AccessTokenService accessTokenService;
    @Inject
    UserContext userContext;

    @GET
    public AccessTokenView getToken() {
        return accessTokenService.find(requireOwnAccount())
                .map(AccessTokenView::of)
                .orElseThrow(() -> new NotFoundException("no access token has been issued"));
    }

    /** Issues or regenerates the user's access token. */
    @PUT
    public IssuedTokenView issueToken() {
        var issued = accessTokenService.issue(requireOwnAccount());
        return new IssuedTokenView(issued.token(), issued.issuedAt());
    }

    private UUID requireOwnAccount() {
        var userId = userContext.require().getId();
        if (SubAccount.isSubAccount(userId)) {
            throw new ForbiddenException("a sub-account's token is managed by its project's owner");
        }
        return userId;
    }
}
