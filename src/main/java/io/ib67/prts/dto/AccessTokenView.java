package io.ib67.prts.dto;

import io.ib67.prts.secret.user.UserAccessToken;

import java.time.Instant;
import java.util.Objects;

/**
 * That a token exists and since when. Like {@link SecretView} there is no value field, not even the
 * hash — the plaintext only ever appears in the {@link IssuedTokenView} that minted it.
 */
public record AccessTokenView(Instant issuedAt) {
    public AccessTokenView {
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    public static AccessTokenView of(UserAccessToken token) {
        return new AccessTokenView(token.getIssuedAt());
    }
}
