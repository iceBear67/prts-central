package io.ib67.prts.secret.user;

import io.ib67.prts.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and resolves personal access tokens.
 *
 * <p>The stored value is a bare digest, without a salt and without a constant-time compare: the
 * token is 256 bits from a CSPRNG, so there is nothing to guess offline and nothing a timing signal
 * narrows down. Determinism is the point — {@link #resolve} hashes what the caller presented and
 * looks that up on an index, rather than reading a row to compare against.
 */
@ApplicationScoped
public class AccessTokenService {

    /** Marks a bearer as ours, so a mechanism can decline someone else's token without a DB read. */
    public static final String PREFIX = "prts_";

    /** As on a sealed secret: which digest a row is under is readable off the row. */
    private static final String HASH_FORMAT = "sha256";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public Optional<UserAccessToken> find(UUID userId) {
        return UserAccessToken.findByIdOptional(userId);
    }

    /** The user a token authenticates, or empty when no row carries its hash. */
    public Optional<User> resolve(String token) {
        return UserAccessToken.findUserByHash(hash(token));
    }

    /**
     * Creates the user's token or rerolls it, returning the plaintext — the only time it exists.
     * Rerolling updates the row in place: a delete and an insert of the same key in one flush would
     * trip the primary key, since Hibernate orders inserts first.
     */
    @Transactional
    public Issued issue(UUID userId) {
        var token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        var issuedAt = Instant.now();
        var result = find(userId);
        if (result.isEmpty()) {
            var created = UserAccessToken.of(requireUser(userId), hash(token));
            created.setIssuedAt(issuedAt);
            created.persist();
        } else {
            var existing = result.get();
            existing.setTokenHash(hash(token));
            existing.setIssuedAt(issuedAt);
        }
        return new Issued(token, issuedAt);
    }

    /** The plaintext and when it was minted — the only time the two exist together. */
    public record Issued(String token, Instant issuedAt) {
        public Issued {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(issuedAt, "issuedAt");
        }
    }

    private byte[] randomBytes() {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String hash(String token) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is required of every JVM", e);
        }
        return HASH_FORMAT + ":" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static User requireUser(UUID id) {
        return User.<User>findByIdOptional(id)
                .orElseThrow(() -> new NoSuchElementException("no such user: " + id));
    }
}
