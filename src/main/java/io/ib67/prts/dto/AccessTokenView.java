package io.ib67.prts.dto;

import io.ib67.prts.secret.user.UserAccessToken;

import java.time.Instant;
import java.util.Objects;

/**
 * View showing the issuance timestamp of an access token.
 */
public record AccessTokenView(Instant issuedAt) {
    public AccessTokenView {
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    public static AccessTokenView of(UserAccessToken token) {
        return new AccessTokenView(token.getIssuedAt());
    }
}
