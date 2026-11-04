package com.adminmod.server.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.security.PasswordVault;

/**
 * Owns the remote administration credential and the directory it lives in.
 *
 * <p>Two files are kept in the restricted directory: {@code credential.dat}, a PBKDF2 verifier
 * used to authenticate sessions, and {@code password.txt}, the generated password itself. The
 * password is stored because the operator must be able to retrieve it from the client's
 * authentication panel; both files are written owner-only and the directory is locked down so a
 * different local account cannot read them.
 *
 * <p>The trade-off is deliberate and documented: keeping only a verifier would make the password
 * unrecoverable, which conflicts with the requirement that the client displays it. The directory
 * permission is therefore the security boundary, exactly as it is for an SSH private key.
 */
public final class CredentialService {

    private static final String VERIFIER_FILE = "credential.dat";
    private static final String PASSWORD_FILE = "password.txt";

    private final Path directory;
    private PasswordVault vault;
    private char[] password;

    private CredentialService(Path directory, PasswordVault vault, char[] password) {
        this.directory = directory;
        this.vault = vault;
        this.password = password;
    }

    /**
     * Loads the credential, creating a fresh one on first run.
     *
     * @param runDirectory the server run directory; {@code remoteAdmin.vaultDirectory} is resolved
     *                     against it
     */
    public static CredentialService open(Path runDirectory, AdminConfig config) throws IOException {
        String configured = config.remoteAdmin.vaultDirectory;
        String folder = configured == null || configured.isBlank() ? PasswordVault.randomFolderName() : configured;
        Path directory = runDirectory.resolve(folder).normalize();
        Files.createDirectories(directory);
        PasswordVault.restrictDirectory(directory);

        Path verifierFile = directory.resolve(VERIFIER_FILE);
        Path passwordFile = directory.resolve(PASSWORD_FILE);

        if (Files.exists(verifierFile) && Files.exists(passwordFile)) {
            PasswordVault vault = PasswordVault.load(verifierFile);
            char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim().toCharArray();
            return new CredentialService(directory, vault, password);
        }

        PasswordVault.Created created = PasswordVault.create();
        created.vault().save(verifierFile);
        PasswordVault.writeRestricted(passwordFile, new String(created.password()));
        return new CredentialService(directory, created.vault(), created.password());
    }

    public PasswordVault vault() {
        return vault;
    }

    /** The operator password, revealed only through the client's authentication panel. */
    public String password() {
        return new String(password);
    }

    /** Directory holding the credential files; reported so an operator can find them. */
    public Path directory() {
        return directory;
    }

    /** True when a verifier file exists on disk. */
    public boolean isPersistent() {
        return Files.exists(directory.resolve(VERIFIER_FILE));
    }

    /**
     * Replaces the operator password with a freshly generated one.
     *
     * <p>A new verifier and password are generated together and both files are rewritten, so the
     * verifier and the displayed password stay consistent. Every existing session is invalidated
     * once the operator re-authenticates with the new password. The previous password is overwritten
     * in memory before it is dropped.
     *
     * @return the new password, to be shown once in the client panel
     */
    public synchronized String rotate() throws IOException {
        PasswordVault.Created created = PasswordVault.create();
        created.vault().save(directory.resolve(VERIFIER_FILE));
        PasswordVault.writeRestricted(directory.resolve(PASSWORD_FILE), new String(created.password()));
        if (password != null) {
            java.util.Arrays.fill(password, '\0');
        }
        this.password = created.password().clone();
        this.vault = created.vault();
        return new String(this.password);
    }
}
