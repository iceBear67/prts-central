package io.ib67.prts.secret;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretCipherTest {

    private static final String CONTEXT = "project:11111111-1111-1111-1111-111111111111";
    private static final String V1 = key((byte) 0x11, 32);
    private static final String V2 = key((byte) 0x22, 32);

    private static String key(byte fill, int length) {
        var raw = new byte[length];
        Arrays.fill(raw, fill);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static SecretCipher cipher(String activeKey, Map<String, String> keys) {
        var config = mock(SecretConfig.class);
        when(config.keys()).thenReturn(keys);
        when(config.activeKey()).thenReturn(activeKey);
        var cipher = new SecretCipher();
        cipher.config = config;
        cipher.loadKeys();
        return cipher;
    }

    private static SecretCipher cipher() {
        return cipher("v1", Map.of("v1", V1));
    }

    @Test
    void sealedValuesOpenAgain() {
        var cipher = cipher();

        assertEquals("hunter2", cipher.open(cipher.seal("hunter2", CONTEXT), CONTEXT));
        assertEquals("", cipher.open(cipher.seal("", CONTEXT), CONTEXT));
        assertEquals("键 🔑", cipher.open(cipher.seal("键 🔑", CONTEXT), CONTEXT));
    }

    @Test
    void anEnvelopeNamesItsFormatAndKey() {
        assertTrue(cipher().seal("hunter2", CONTEXT).startsWith("1:v1:"));
    }

    @Test
    void sealingTwiceProducesDifferentCiphertext() {
        var cipher = cipher();

        assertNotEquals(cipher.seal("hunter2", CONTEXT), cipher.seal("hunter2", CONTEXT));
    }

    @Test
    void aValueDoesNotOpenUnderAnotherContext() {
        var cipher = cipher();
        var sealed = cipher.seal("hunter2", CONTEXT);

        assertThrows(IllegalStateException.class, () -> cipher.open(sealed, "project:other"));
    }

    @Test
    void aValueDoesNotOpenUnderAnotherKey() {
        var sealed = cipher().seal("hunter2", CONTEXT);
        var rekeyed = cipher("v2", Map.of("v1", V2, "v2", V2));

        assertThrows(IllegalStateException.class, () -> rekeyed.open(sealed, CONTEXT));
    }

    /** Verifies that ciphertexts encrypted with previous keys remain decryptable after key rotation. */
    @Test
    void rotationKeepsOldValuesReadable() {
        var sealed = cipher().seal("hunter2", CONTEXT);
        var rotated = cipher("v2", Map.of("v1", V1, "v2", V2));

        assertEquals("hunter2", rotated.open(sealed, CONTEXT));
        assertTrue(rotated.seal("hunter2", CONTEXT).startsWith("1:v2:"));
    }

    @Test
    void droppingAKeyStillInUseIsReported() {
        var sealed = cipher().seal("hunter2", CONTEXT);
        var dropped = cipher("v2", Map.of("v2", V2));

        var error = assertThrows(IllegalStateException.class, () -> dropped.open(sealed, CONTEXT));
        assertTrue(error.getMessage().contains("v1"), error.getMessage());
    }

    @Test
    void aMalformedEnvelopeIsRejected() {
        var cipher = cipher();

        assertThrows(IllegalStateException.class, () -> cipher.open("not-an-envelope", CONTEXT));
        assertThrows(IllegalStateException.class, () -> cipher.open("2:v1:AAAA", CONTEXT));
        assertThrows(IllegalStateException.class, () -> cipher.open("1:v1:not base64!", CONTEXT));
        // 12 bytes corresponds to the IV only, without ciphertext or authentication tag.
        assertThrows(IllegalStateException.class,
                () -> cipher.open("1:v1:" + key((byte) 0, 12), CONTEXT));
    }

    @Test
    void aTamperedEnvelopeDoesNotOpen() {
        var cipher = cipher();
        var sealed = cipher.seal("hunter2", CONTEXT);
        var payload = Base64.getDecoder().decode(sealed.substring("1:v1:".length()));
        payload[payload.length - 1] ^= 0x01;
        var tampered = "1:v1:" + Base64.getEncoder().encodeToString(payload);

        assertThrows(IllegalStateException.class, () -> cipher.open(tampered, CONTEXT));
    }

    @Test
    void anActiveKeyMustBeOneOfTheConfiguredKeys() {
        assertThrows(IllegalStateException.class, () -> cipher("v9", Map.of("v1", V1)));
        assertThrows(IllegalStateException.class, () -> cipher("v1", Map.of()));
    }

    @Test
    void keyMaterialIsValidatedAtStartup() {
        assertThrows(IllegalStateException.class, () -> cipher("v1", Map.of("v1", "not base64!")));
        // 20 bytes is not a valid AES key length (expected 16, 24, or 32 bytes).
        assertThrows(IllegalStateException.class, () -> cipher("v1", Map.of("v1", key((byte) 1, 20))));
        assertThrows(IllegalStateException.class, () -> cipher("bad id", Map.of("bad id", V1)));
    }

    @Test
    void aes128And192KeysAreAccepted() {
        for (var length : new int[]{16, 24, 32}) {
            var cipher = cipher("v1", Map.of("v1", key((byte) 3, length)));
            assertEquals("hunter2", cipher.open(cipher.seal("hunter2", CONTEXT), CONTEXT));
        }
    }
}
