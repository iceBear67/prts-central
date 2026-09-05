package io.ib67.prts.secret.user.resource;

import io.ib67.prts.dto.AccessTokenView;
import io.ib67.prts.dto.IssuedTokenView;
import io.ib67.prts.secret.user.AccessTokenService;
import io.ib67.prts.user.SubAccount;
import io.ib67.prts.user.UserContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * The caller's own access token. No {@code @RequirePermission}: like {@code POST /project/{id}/leave}
 * these only ever touch the caller, so the identity having a local user is the whole check.
 *
 * <p>Reachable with a token as well as with a session — that is the equivalence, and for a person
 * rerolling costs nothing that whoever already holds the token could not do anyway. Not so for a
 * sub-account: its token is the only credential it has and its project's owner minted it, so letting
 * the holder reroll it would take the credential away from the owner. A sub-account is refused here
 * and managed from {@code SubAccountResource} only.
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

    /** Idempotent in shape, not in value: every call replaces whatever token was there. */
    @PUT
    public IssuedTokenView issueToken() {
        var issued = accessTokenService.issue(requireOwnAccount());
        return new IssuedTokenView(issued.token(), issued.issuedAt());
    }

    @DELETE
    public void revokeToken() {
        if (!accessTokenService.revoke(requireOwnAccount())) {
            throw new NotFoundException("no access token has been issued");
        }
    }

    private UUID requireOwnAccount() {
        var userId = userContext.require().getId();
        if (SubAccount.isSubAccount(userId)) {
            throw new ForbiddenException("a sub-account's token is managed by its project's owner");
        }
        return userId;
    }
}
