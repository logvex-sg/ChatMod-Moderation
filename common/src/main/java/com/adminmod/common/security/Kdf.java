package com.adminmod.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives the per-session traffic key.
 *
 * <p>The shared secret (the operator password) is only ever held as a PBKDF2 output. The
 * session key additionally mixes in both handshake nonces, so two sessions that reuse the
 * password still produce different traffic keys and a captured transcript cannot be replayed
 * into a later session.
 */
public final class Kdf {

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final byte[] KDF_LABEL = "adminmod-session-v1".getBytes(StandardCharsets.UTF_8);

    private Kdf() {
    }

    /** Salt used for the at-rest password verifier. Stable so restarts keep verifying. */
    public static byte[] passwordSalt() {
        return "adminmod:password-verifier:v1".getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] derivePasswordKey(char[] password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, passwordSalt(), PBKDF2_ITERATIONS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    /**
     * Mixes the password key with both handshake nonces to obtain the session traffic key.
     * Order matters: the client and the server must pass their nonces in the same positions.
     */
    public static byte[] deriveSessionKey(byte[] passwordKey, byte[] clientNonce, byte[] serverNonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(passwordKey, "HmacSHA256"));
            mac.update(KDF_LABEL);
            mac.update(clientNonce);
            mac.update(serverNonce);
            byte[] key = mac.doFinal();
            Arrays.fill(passwordKey, (byte) 0);
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time comparison for fixed-length secrets. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
