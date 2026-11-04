package com.adminmod.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM wrapper for the session traffic key.
 *
 * <p>The nonce and timestamp travel in the clear as part of the {@code Envelope}, so they are
 * also passed here as additional authenticated data. A tampered timestamp therefore fails
 * authentication instead of silently widening the replay window.
 */
public final class SessionCipher {

    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    public SessionCipher(byte[] sessionKey) {
        if (sessionKey.length != 32) {
            throw new IllegalArgumentException("session key must be 32 bytes");
        }
        this.key = new SecretKeySpec(sessionKey, "AES");
    }

    public byte[] encrypt(byte[] plaintext, long nonce, long timestamp) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonceBytes(nonce)));
            cipher.updateAAD(aad(timestamp));
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, long nonce, long timestamp) throws SessionException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonceBytes(nonce)));
            cipher.updateAAD(aad(timestamp));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new SessionException("message authentication failed");
        }
    }

    private static byte[] nonceBytes(long nonce) {
        return ByteBuffer.allocate(12).putLong(0L).putInt((int) nonce).array();
    }

    private static byte[] aad(long timestamp) {
        return ByteBuffer.allocate(8).putLong(timestamp).array();
    }

    /** Derives a stable, non-secret fingerprint used to confirm both sides share a key. */
    public String fingerprint() {
        byte[] raw = key.getEncoded();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            sb.append(Character.forDigit((raw[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(raw[i] & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] labelBytes() {
        return "adminmod".getBytes(StandardCharsets.UTF_8);
    }
}
