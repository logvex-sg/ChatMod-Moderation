package com.adminmod.server.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.security.ClientRegistry;
import com.adminmod.common.security.Kdf;
import com.adminmod.common.security.PasswordVault;
import com.adminmod.common.security.SecretRedactor;

/**
 * Owns the paired-client registry and the one-time pairing keys.
 *
 * <p>The registry is a file of PBKDF2 verifiers; a pairing key and a client secret are never
 * written in the clear, so the file itself is not a credential. A pairing key is stored only as a
 * verifier, is valid for a bounded time, and is consumed the first time it is redeemed, so it
 * cannot be used twice even by an observer who captured it.
 *
 * <p>Every method here is server-only. The client never sees a pending key, another client's
 * secret, or the registry contents beyond the label and activity of its own entry.
 */
public final class PairingService {

    /** A freshly generated one-time pairing key, returned to the caller exactly once. */
    public record IssuedKey(String key, long expiresAt) {
    }

    /** The outcome of redeeming a pairing key. */
    public record Paired(boolean success, String clientId, String clientSecret, String message) {
    }

    private final AdminConfig config;
    private final ClientRegistry registry;
    private final Path file;

    public PairingService(AdminConfig config, Path file) throws IOException {
        this.config = config;
        this.file = file;
        this.registry = new ClientRegistry(file);
        registry.load();
    }

    public ClientRegistry registry() {
        return registry;
    }

    public Path file() {
        return file;
    }

    /**
     * Mints a one-time pairing key.
     *
     * <p>Only the verifier is persisted; the returned key is the only copy, so it must be shown to
     * the owner immediately. It expires after {@code pairingKeyTtlSeconds} and is single use.
     */
    public synchronized IssuedKey issueKey(String createdBy) throws IOException {
        String key = PasswordVault.generateSecret(Math.max(12, config.remoteAdmin.pairingKeyLength));
        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(30L, config.remoteAdmin.pairingKeyTtlSeconds) * 1000L;
        registry.addPendingKey(base64(Kdf.derivePairingKey(key)), expiresAt, createdBy, now);
        registry.save();
        return new IssuedKey(key, expiresAt);
    }

    /** Verifiers of every pending key, so a pairing handshake can match one by decryption. */
    public synchronized List<byte[]> pendingVerifiers() {
        List<byte[]> verifiers = new ArrayList<>();
        for (ClientRegistry.PendingKey key : registry.pendingKeyList()) {
            if (key.verifier() != null && !key.verifier().isBlank()) {
                verifiers.add(java.util.Base64.getDecoder().decode(key.verifier()));
            }
        }
        return verifiers;
    }

    /**
     * Redeems a pairing key for a new client identity.
     *
     * <p>The key is checked against the pending set and consumed on success, so it cannot be reused.
     * The issued secret is returned to the caller, who forwards it to the client inside the encrypted
     * pairing result; only its verifier is stored.
     */
    public synchronized Paired pair(String pairingKey, String label, String buildId, String ownerUuid,
            String ownerName) throws IOException {
        if (!config.remoteAdmin.allowPairing) {
            return new Paired(false, "", "", "pairing is disabled on this server");
        }
        long now = System.currentTimeMillis();
        byte[] candidate = Kdf.derivePairingKey(pairingKey == null ? "" : pairingKey.trim());
        if (!registry.consumePendingKey(candidate, now)) {
            return new Paired(false, "", "", "the pairing key is invalid, already used, or expired");
        }
        String secret = PasswordVault.generateSecret(Math.max(24, config.remoteAdmin.clientSecretLength));
        byte[] verifier = Kdf.deriveClientKey(secret);
        String safeLabel = sanitizeLabel(label);
        ClientRegistry.Client client = registry.addClient(safeLabel, verifier, ownerUuid, ownerName, buildId, now);
        registry.save();
        return new Paired(true, client.id(), secret, "paired as " + client.id());
    }

    /**
     * Redeems the pending key that a pairing session actually matched.
     *
     * <p>The session proved which candidate key the client holds by decrypting its first frame, so
     * this consumes that exact key rather than re-checking the secret. It is the same single-use
     * rule as {@link #pair}, applied to the already-proven candidate.
     */
    public synchronized Paired pairFromMatched(byte[] matchedVerifier, String label, String buildId,
            String ownerUuid, String ownerName) throws IOException {
        if (!config.remoteAdmin.allowPairing) {
            return new Paired(false, "", "", "pairing is disabled on this server");
        }
        long now = System.currentTimeMillis();
        if (matchedVerifier == null || !registry.consumePendingKey(matchedVerifier, now)) {
            return new Paired(false, "", "", "the pairing key is invalid, already used, or expired");
        }
        String secret = PasswordVault.generateSecret(Math.max(24, config.remoteAdmin.clientSecretLength));
        byte[] verifier = Kdf.deriveClientKey(secret);
        String safeLabel = sanitizeLabel(label);
        ClientRegistry.Client client = registry.addClient(safeLabel, verifier, ownerUuid, ownerName, buildId, now);
        registry.save();
        return new Paired(true, client.id(), secret, "paired as " + client.id());
    }

    /** Revokes a paired client. @return whether a client was removed. */
    public synchronized boolean revoke(String clientId) throws IOException {
        boolean removed = registry.revoke(clientId);
        if (removed) {
            registry.save();
        }
        return removed;
    }

    /**
     * Rotates a paired client's secret, invalidating the old one.
     *
     * @return the new secret, or {@code null} when the client is unknown
     */
    public synchronized String rotateClientSecret(String clientId) throws IOException {
        if (registry.client(clientId) == null) {
            return null;
        }
        String secret = PasswordVault.generateSecret(Math.max(24, config.remoteAdmin.clientSecretLength));
        registry.rotateVerifier(clientId, Kdf.deriveClientKey(secret), System.currentTimeMillis());
        registry.save();
        return secret;
    }

    /** @return the decoded verifier for a client, or {@code null} when it is unknown. */
    public synchronized byte[] verifierOf(String clientId) {
        return registry.verifierOf(clientId);
    }

    public synchronized void touch(String clientId, String address, String protocol) {
        registry.touch(clientId, System.currentTimeMillis(), address, protocol);
        try {
            registry.save();
        } catch (IOException ignored) {
            // Activity is advisory; a failed write must not block authentication.
        }
    }

    /** Whether a client id is known, without revealing anything else about it. */
    public synchronized boolean isKnown(String clientId) {
        return clientId != null && !clientId.isBlank() && registry.client(clientId) != null;
    }

    public synchronized int clientCount() {
        return registry.clients().size();
    }

    public synchronized int pendingKeyCount() {
        return registry.pendingKeyCount(System.currentTimeMillis());
    }

    /**
     * A label is attacker-influenced, so it is stripped of control characters and length-bounded
     * before it is stored or rendered in the dashboard.
     */
    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "unnamed client";
        }
        String cleaned = SecretRedactor.redact(label).replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty()) {
            return "unnamed client";
        }
        return cleaned.length() <= 48 ? cleaned : cleaned.substring(0, 48);
    }

    private static String base64(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
