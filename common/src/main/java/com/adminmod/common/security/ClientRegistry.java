package com.adminmod.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Durable, restricted store of paired clients and one-time pairing keys.
 *
 * <p>The file holds only verifiers, never a pairing key or a client secret in the clear: both are
 * hashed with PBKDF2 before being written, so the file is not a credential in itself. It is written
 * owner-only through the same helper the moderation history uses.
 *
 * <p>The store has no Minecraft dependency, so pairing, revocation and persistence can be unit
 * tested on a plain JVM.
 */
public final class ClientRegistry {

    /** A client that completed pairing and may authenticate. */
    public record Client(String id, String label, String verifier, String ownerUuid, String ownerName,
            String buildId, long pairedAt, long lastSeenAt, String lastAddress, String lastProtocol) {
    }

    /** A one-time pairing key that has not been redeemed and has not expired. */
    public record PendingKey(String verifier, long expiresAt, String createdBy, long createdAt) {
    }

    private final Path file;
    private final List<Client> clients = new ArrayList<>();
    private final List<PendingKey> pendingKeys = new ArrayList<>();
    private long nextClientId = 1L;

    public ClientRegistry(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        clients.clear();
        pendingKeys.clear();
        nextClientId = 1L;
        if (Files.notExists(file)) {
            return;
        }
        JsonObject root = parse(Files.readString(file, StandardCharsets.UTF_8));
        nextClientId = root.has("nextClientId") ? root.get("nextClientId").getAsLong() : 1L;
        JsonArray clientArray = root.getAsJsonArray("clients");
        if (clientArray != null) {
            for (JsonElement element : clientArray) {
                JsonObject object = element.getAsJsonObject();
                clients.add(new Client(
                        str(object, "id", ""),
                        str(object, "label", ""),
                        str(object, "verifier", ""),
                        str(object, "ownerUuid", ""),
                        str(object, "ownerName", ""),
                        str(object, "buildId", ""),
                        lng(object, "pairedAt", 0L),
                        lng(object, "lastSeenAt", 0L),
                        str(object, "lastAddress", ""),
                        str(object, "lastProtocol", "")));
            }
        }
        JsonArray keyArray = root.getAsJsonArray("pendingKeys");
        if (keyArray != null) {
            for (JsonElement element : keyArray) {
                JsonObject object = element.getAsJsonObject();
                pendingKeys.add(new PendingKey(
                        str(object, "verifier", ""),
                        lng(object, "expiresAt", 0L),
                        str(object, "createdBy", ""),
                        lng(object, "createdAt", 0L)));
            }
        }
        for (Client client : clients) {
            nextClientId = Math.max(nextClientId, numericId(client.id()) + 1);
        }
    }

    public synchronized void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("nextClientId", nextClientId);
        JsonArray clientArray = new JsonArray();
        for (Client client : clients) {
            JsonObject object = new JsonObject();
            object.addProperty("id", client.id());
            object.addProperty("label", client.label());
            object.addProperty("verifier", client.verifier());
            object.addProperty("ownerUuid", client.ownerUuid());
            object.addProperty("ownerName", client.ownerName());
            object.addProperty("buildId", client.buildId());
            object.addProperty("pairedAt", client.pairedAt());
            object.addProperty("lastSeenAt", client.lastSeenAt());
            object.addProperty("lastAddress", client.lastAddress());
            object.addProperty("lastProtocol", client.lastProtocol());
            clientArray.add(object);
        }
        root.add("clients", clientArray);
        JsonArray keyArray = new JsonArray();
        for (PendingKey key : pendingKeys) {
            JsonObject object = new JsonObject();
            object.addProperty("verifier", key.verifier());
            object.addProperty("expiresAt", key.expiresAt());
            object.addProperty("createdBy", key.createdBy());
            object.addProperty("createdAt", key.createdAt());
            keyArray.add(object);
        }
        root.add("pendingKeys", keyArray);
        Files.createDirectories(file.getParent());
        PasswordVault.writeRestricted(file,
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    /** Adds a one-time pairing key, dropping any that have already expired. */
    public synchronized void addPendingKey(String verifier, long expiresAt, String createdBy, long now) {
        pruneKeys(now);
        pendingKeys.add(new PendingKey(verifier, expiresAt, createdBy, now));
    }

    /**
     * Consumes a pairing key if it matches an unexpired pending entry.
     *
     * @return {@code true} when the key was valid; a key can only ever be consumed once
     */
    public synchronized boolean consumePendingKey(byte[] candidateVerifier, long now) {
        pruneKeys(now);
        for (int i = 0; i < pendingKeys.size(); i++) {
            PendingKey key = pendingKeys.get(i);
            if (now > key.expiresAt()) {
                continue;
            }
            byte[] stored = decodeVerifier(key.verifier());
            if (Kdf.constantTimeEquals(stored, candidateVerifier)) {
                pendingKeys.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Registers a freshly paired client and returns it. */
    public synchronized Client addClient(String label, byte[] verifier, String ownerUuid, String ownerName,
            String buildId, long now) {
        String id = "c" + nextClientId++;
        Client client = new Client(id, label, encodeVerifier(verifier), ownerUuid, ownerName, buildId, now, now, "",
                "");
        clients.add(client);
        return client;
    }

    /** @return the client with the given id, or {@code null}. */
    public synchronized Client client(String id) {
        for (Client client : clients) {
            if (client.id().equals(id)) {
                return client;
            }
        }
        return null;
    }

    /** @return the decoded secret verifier for a client, or {@code null} when it is unknown. */
    public synchronized byte[] verifierOf(String id) {
        Client client = client(id);
        return client == null ? null : decodeVerifier(client.verifier());
    }

    /** Records that a client authenticated, so the dashboard can show activity. */
    public synchronized void touch(String id, long now, String address, String protocol) {
        for (int i = 0; i < clients.size(); i++) {
            Client client = clients.get(i);
            if (client.id().equals(id)) {
                clients.set(i, new Client(client.id(), client.label(), client.verifier(), client.ownerUuid(),
                        client.ownerName(), client.buildId(), client.pairedAt(), now, address, protocol));
                return;
            }
        }
    }

    /** Replaces a client's stored verifier, used by credential rotation. @return true if it existed. */
    public synchronized boolean rotateVerifier(String id, byte[] verifier, long now) {
        for (int i = 0; i < clients.size(); i++) {
            Client client = clients.get(i);
            if (client.id().equals(id)) {
                clients.set(i, new Client(client.id(), client.label(), encodeVerifier(verifier), client.ownerUuid(),
                        client.ownerName(), client.buildId(), client.pairedAt(), now, client.lastAddress(),
                        client.lastProtocol()));
                return true;
            }
        }
        return false;
    }

    /** Revokes a paired client. @return true when a client was removed. */
    public synchronized boolean revoke(String id) {
        return clients.removeIf(client -> client.id().equals(id));
    }

    public synchronized List<Client> clients() {
        return List.copyOf(clients);
    }

    /** The pending keys, including any that have just expired and not yet been pruned. */
    public synchronized List<PendingKey> pendingKeyList() {
        return List.copyOf(pendingKeys);
    }

    public synchronized int pendingKeyCount(long now) {
        pruneKeys(now);
        return pendingKeys.size();
    }

    private void pruneKeys(long now) {
        pendingKeys.removeIf(key -> now > key.expiresAt());
    }

    private static String encodeVerifier(byte[] verifier) {
        return java.util.Base64.getEncoder().encodeToString(verifier);
    }

    private static byte[] decodeVerifier(String encoded) {
        try {
            return java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static long numericId(String id) {
        if (id == null || id.isEmpty() || id.charAt(0) != 'c') {
            return 0L;
        }
        try {
            return Long.parseLong(id.substring(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static JsonObject parse(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static long lng(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }
}
