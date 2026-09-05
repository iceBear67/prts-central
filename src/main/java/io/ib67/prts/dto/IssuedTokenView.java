package io.ib67.prts.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * The one response that carries a token in the clear. Only what stored it can produce this: the
 * server keeps a hash, so a lost token is rerolled, never recovered.
 */
public record IssuedTokenView(String token, Instant issuedAt) {
    public IssuedTokenView {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
