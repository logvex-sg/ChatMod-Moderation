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

    /** Salt used for the at-rest pairing-key verifier. Distinct from the password salt. */
    public static byte[] pairingSalt() {
        return "adminmod:pairing-verifier:v1".getBytes(StandardCharsets.UTF_8);
    }

    /** Salt used for the at-rest paired-client secret verifier. */
    public static byte[] clientSalt() {
        return "adminmod:client-verifier:v1".getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] derivePasswordKey(char[] password) {
        return deriveKey(password, passwordSalt());
    }

    /**
     * Derives the verifier for a one-time pairing key.
     *
     * <p>Only the verifier is stored, so a leaked registry file does not disclose a pairing key
     * that could still be redeemed.
     */
    public static byte[] derivePairingKey(String pairingKey) {
        return deriveKey(pairingKey.toCharArray(), pairingSalt());
    }

    /**
     * Derives the verifier for a paired client's long-lived secret.
     *
     * <p>The client derives the same verifier from the secret it holds and mixes it with the
     * handshake nonces to obtain the session key; the server keeps only the verifier, so the raw
     * client secret is never stored. This is what lets a pairing credential be verified without
     * keeping a plaintext secret on disk.
     */
    public static byte[] deriveClientKey(String clientSecret) {
        return deriveKey(clientSecret.toCharArray(), clientSalt());
    }

    private static byte[] deriveKey(char[] secret, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret, salt, PBKDF2_ITERATIONS, KEY_BITS);
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
        return deriveSessionKey(passwordKey, 0, clientNonce, serverNonce);
    }

    /**
     * Mode-aware session key derivation.
     *
     * <p>The authentication mode is bound into the transcript, so a frame sealed in one mode can
     * never be accepted in another. Without this a pairing transcript could be replayed against
     * the password path (or the reverse), because both would otherwise mix the same nonces with a
     * 32-byte credential key.
     */
    public static byte[] deriveSessionKey(byte[] credentialKey, int modeId, byte[] clientNonce, byte[] serverNonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(credentialKey, "HmacSHA256"));
            mac.update(KDF_LABEL);
            mac.update((byte) modeId);
            mac.update(clientNonce);
            mac.update(serverNonce);
            byte[] key = mac.doFinal();
            Arrays.fill(credentialKey, (byte) 0);
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
