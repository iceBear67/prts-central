package io.ib67.prts.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * View returned upon token creation containing the plaintext token value.
 * Plaintext tokens cannot be retrieved after initial issuance.
 */
public record IssuedTokenView(String token, Instant issuedAt) {
    public IssuedTokenView {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
