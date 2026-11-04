package com.adminmod.client.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Stores the paired-client identity on the operator's own machine.
 *
 * <p>It holds one client id and its secret, written owner-only. The secret is a bearer credential,
 * so it is only ever sent inside an encrypted frame; it is never displayed, logged or written
 * anywhere else. The file is the client's half of the pairing, and deleting it simply unpairs this
 * machine.
 */
public final class ClientCredentialStore {

    private static final String FILE_NAME = "adminmod-client.json";

    private String clientId = "";
    private String clientSecret = "";

    private ClientCredentialStore() {
    }

    public static ClientCredentialStore empty() {
        return new ClientCredentialStore();
    }

    /** @return the stored credential, or an empty store when none exists or the file is unreadable. */
    public static ClientCredentialStore load(Path directory) {
        ClientCredentialStore store = new ClientCredentialStore();
        Path file = directory.resolve(FILE_NAME);
        if (Files.notExists(file)) {
            return store;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            store.clientId = extract(json, "clientId");
            store.clientSecret = extract(json, "clientSecret");
        } catch (IOException e) {
            // An unreadable store simply means this machine is not paired.
            return new ClientCredentialStore();
        }
        return store;
    }

    public void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(FILE_NAME);
        String json = "{\n  \"clientId\": \"" + escape(clientId) + "\",\n  \"clientSecret\": \""
                + escape(clientSecret) + "\"\n}\n";
        Files.writeString(file, json, StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system; nothing further can be enforced here.
        }
    }

    public boolean isPaired() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public void set(String clientId, String clientSecret) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
    }

    public void clear() {
        clientId = "";
        clientSecret = "";
    }

    /**
     * Minimal field reader.
     *
     * <p>The store has exactly two string fields, so a tiny scanner is used instead of pulling Gson
     * into the client jar. It tolerates the whitespace the writer produces.
     */
    private static String extract(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return "";
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return "";
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return "";
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return "";
        }
        return unescape(json.substring(open + 1, close));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
