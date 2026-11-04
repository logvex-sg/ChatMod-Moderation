package com.adminmod.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Stores the remote administration credential.
 *
 * <p>Only a PBKDF2 verifier is written to disk, never the password itself, so the on-disk
 * material cannot be turned back into a usable credential. The generated password is shown
 * exactly once - in the client's authentication panel - and the operator is expected to record
 * it there.
 */
public final class PasswordVault {

    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 28;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] verifier;

    private PasswordVault(byte[] verifier) {
        this.verifier = verifier;
    }

    /** Creates a new vault with a freshly generated password and returns both. */
    public static Created create() {
        char[] password = generatePassword();
        byte[] verifier = Kdf.derivePasswordKey(password);
        return new Created(new PasswordVault(verifier), password);
    }

    public static PasswordVault load(Path file) throws IOException {
        byte[] verifier = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.UTF_8).trim());
        if (verifier.length != 32) {
            throw new IOException("corrupt credential file");
        }
        return new PasswordVault(verifier);
    }

    /** Wraps an already-derived verifier, used when the password is rotated. */
    public static PasswordVault fromVerifier(byte[] verifier) {
        if (verifier == null || verifier.length != 32) {
            throw new IllegalArgumentException("verifier must be 32 bytes");
        }
        return new PasswordVault(verifier.clone());
    }

    /**
     * Generates a high-entropy secret from the password alphabet.
     *
     * <p>Used for one-time pairing keys and paired-client secrets as well as the operator password,
     * so every generated credential has the same shape and the same entropy guarantee.
     */
    public static String generateSecret(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        writeRestricted(file, Base64.getEncoder().encodeToString(verifier));
    }

    /** Constant-time verification of a candidate password. */
    public boolean verify(char[] candidate) {
        byte[] candidateVerifier = Kdf.derivePasswordKey(candidate);
        return Kdf.constantTimeEquals(verifier, candidateVerifier);
    }

    /**
     * Copy of the stored password key, used to mix a session key. The caller owns the copy and
     * is expected to let {@link Kdf#deriveSessionKey} zero it.
     */
    public byte[] verifierCopy() {
        return verifier.clone();
    }

    public static char[] generatePassword() {
        char[] chars = new char[PASSWORD_LENGTH];
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            chars[i] = PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length()));
        }
        return chars;
    }

    /** Random, unguessable directory name for the credential store. */
    public static String randomFolderName() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(48);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] randomNonce(int length) {
        byte[] nonce = new byte[length];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    /**
     * Writes a file that only the owning account can read. Falls back silently on file systems
     * without POSIX permissions (for example Windows), where the parent directory is the only
     * remaining protection.
     */
    public static void writeRestricted(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file system; nothing further can be enforced here.
        }
    }

    public static void restrictDirectory(Path directory) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(directory, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system or permission change refused; nothing further to do.
        }
    }

    /** Result of {@link #create()}: the vault plus the one-time password to display. */
    public record Created(PasswordVault vault, char[] password) {
    }
}
