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
 * Service for issuing and resolving personal user access tokens.
 */
@ApplicationScoped
public class AccessTokenService {

    /** Prefix for all personal access tokens. */
    public static final String PREFIX = "prts_";

    private static final String HASH_FORMAT = "sha256";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public Optional<UserAccessToken> find(UUID userId) {
        return UserAccessToken.findByIdOptional(userId);
    }

    /** Resolves the user associated with the given raw token string. */
    public Optional<User> resolve(String token) {
        return UserAccessToken.findUserByHash(hash(token));
    }

    /**
     * Issues a new access token or regenerates an existing one for the specified user.
     *
     * @param userId the user ID
     * @return the issued token plaintext and issue timestamp
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

    /** Represents an issued plaintext access token and its creation timestamp. */
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
