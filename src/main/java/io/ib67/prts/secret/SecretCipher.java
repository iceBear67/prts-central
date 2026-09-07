package io.ib67.prts.secret;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Handles AES-GCM encryption and decryption of secrets using configured keys.
 *
 * <p>Values are encoded as {@code <format>:<keyId>:<base64(iv || ciphertext || tag)>}.
 * The header and context string are included as Additional Authenticated Data (AAD)
 * to guarantee authenticity and prevent tampering across different projects or keys.
 */
@Startup
@ApplicationScoped
public class SecretCipher {
    private static final String FORMAT = "1";
    private static final char SEPARATOR = ':';
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    @Inject
    SecretConfig config;

    private Map<String, SecretKeySpec> keys;
    private String activeKeyId;

    /** Validates and loads encryption keys at application startup. */
    @PostConstruct
    void loadKeys() {
        var loaded = new HashMap<String, SecretKeySpec>();
        config.keys().forEach((id, material) -> loaded.put(requireKeyId(id), keyOf(id, material)));
        activeKeyId = config.activeKey().strip();
        if (!loaded.containsKey(activeKeyId)) {
            throw new IllegalStateException("secret.active-key is " + activeKeyId
                    + ", which is not one of secret.keys " + loaded.keySet());
        }
        keys = Map.copyOf(loaded);
    }

    /**
     * Encrypts plaintext under the active key with associated context as AAD.
     */
    public String seal(String plaintext, String context) {
        var iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        var header = FORMAT + SEPARATOR + activeKeyId;
        var sealed = apply(Cipher.ENCRYPT_MODE, activeKeyId, iv, aad(header, context), plaintext.getBytes(UTF_8));
        var blob = new byte[IV_LENGTH + sealed.length];
        System.arraycopy(iv, 0, blob, 0, IV_LENGTH);
        System.arraycopy(sealed, 0, blob, IV_LENGTH, sealed.length);
        return header + SEPARATOR + Base64.getEncoder().encodeToString(blob);
    }

    /**
     * Decrypts an encrypted envelope using the specified context.
     */
    public String open(String stored, String context) {
        var envelope = parse(stored);
        var iv = Arrays.copyOf(envelope.payload(), IV_LENGTH);
        var sealed = Arrays.copyOfRange(envelope.payload(), IV_LENGTH, envelope.payload().length);
        var opened = apply(Cipher.DECRYPT_MODE, envelope.keyId(), iv, aad(envelope.header(), context), sealed);
        return new String(opened, UTF_8);
    }

    private static String requireKeyId(String id) {
        if (!KEY_ID.matcher(id).matches()) {
            throw new IllegalStateException(
                    "a secret.keys id must match " + KEY_ID.pattern() + ", got " + id);
        }
        return id;
    }

    private static SecretKeySpec keyOf(String id, String material) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(material.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("secret.keys." + id + " is not base64", e);
        }
        if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
            throw new IllegalStateException("secret.keys." + id + " must decode to 16, 24 or 32 bytes, got "
                    + decoded.length + "; generate one with `openssl rand -base64 32`");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private static Envelope parse(String stored) {
        var parts = stored.split(String.valueOf(SEPARATOR), 3);
        if (parts.length != 3 || !FORMAT.equals(parts[0]) || !KEY_ID.matcher(parts[1]).matches()) {
            throw new IllegalStateException("stored secret is not a format " + FORMAT + " envelope");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stored secret is not base64", e);
        }
        if (payload.length <= IV_LENGTH) {
            throw new IllegalStateException("stored secret is truncated");
        }
        return new Envelope(parts[0] + SEPARATOR + parts[1], parts[1], payload);
    }

    private static byte[] aad(String header, String context) {
        return (header + SEPARATOR + context).getBytes(UTF_8);
    }

    private byte[] apply(int mode, String keyId, byte[] iv, byte[] aad, byte[] input) {
        var key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("no secret.keys entry named " + keyId
                    + ", which stored secrets are still sealed under");
        }
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // Do not include exception details to avoid leaking sensitive information
            throw new IllegalStateException(
                    mode == Cipher.ENCRYPT_MODE ? "failed to seal a secret" : "failed to open a secret", e);
        }
    }

    private record Envelope(String header, String keyId, byte[] payload) {
        private Envelope {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(payload, "payload");
        }
    }
}
