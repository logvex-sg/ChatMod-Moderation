package com.adminmod.server.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.adminmod.common.net.AuthMode;
import com.adminmod.common.net.HandshakeMessage;
import com.adminmod.common.security.Kdf;
import com.adminmod.common.security.PasswordVault;
import com.adminmod.common.security.ReplayGuard;
import com.adminmod.common.security.SessionCipher;
import com.adminmod.common.security.SessionException;
import com.adminmod.common.security.SlidingWindowRateLimiter;

/**
 * Server-side state for one remote administration connection.
 *
 * <p>A session is created when the client sends its handshake and only becomes usable once the
 * client proves possession of its credential by sending a correctly authenticated first frame.
 * The session key mixes both handshake nonces, so the credential itself never travels over the
 * wire.
 *
 * <p>For the one-time pairing mode the server cannot know in advance which pending key the client
 * used, so several candidate keys are tried. Each candidate produces a different session key, and
 * the one whose cipher authenticates the first frame is the key the client actually holds. This is
 * why the class holds a list of ciphers rather than one: the match is discovered by decryption, not
 * by anything the client claims.
 */
public final class RemoteSession {

    public enum State {
        /** Handshake received, key derived, waiting for the first authenticated frame. */
        PENDING,
        /** Key proven; requests are accepted. */
        AUTHENTICATED,
        /** Closed by either side; no further frames are accepted. */
        CLOSED
    }

    private final UUID playerUuid;
    private final String playerName;
    private final AuthMode authMode;
    private final String claimedClientId;
    private final List<SessionCipher> candidates;
    private final List<byte[]> candidateVerifiers;
    private final ReplayGuard replayGuard = new ReplayGuard();
    private final SlidingWindowRateLimiter generalLimiter;
    private final SlidingWindowRateLimiter consoleLimiter;

    private volatile SessionCipher activeCipher;
    private volatile int matchedIndex = -1;
    private volatile State state = State.PENDING;
    private volatile boolean owner;
    private volatile int permissionLevel;
    private volatile long lastActivityMillis = System.currentTimeMillis();

    private RemoteSession(UUID playerUuid, String playerName, AuthMode authMode, String claimedClientId,
            List<SessionCipher> candidates, List<byte[]> candidateVerifiers, int requestsPerMinute,
            int consoleRequestsPerMinute) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.authMode = authMode;
        this.claimedClientId = claimedClientId == null ? "" : claimedClientId;
        this.candidates = List.copyOf(candidates);
        this.candidateVerifiers = List.copyOf(candidateVerifiers);
        this.generalLimiter = new SlidingWindowRateLimiter(requestsPerMinute, 60_000L);
        this.consoleLimiter = new SlidingWindowRateLimiter(consoleRequestsPerMinute, 60_000L);
    }

    /**
     * Builds a session whose single key is derived from one credential verifier.
     *
     * @param credentialVerifier a 32-byte PBKDF2 verifier; it is copied, never retained
     */
    public static RemoteSession single(UUID playerUuid, String playerName, AuthMode mode, String clientId,
            byte[] credentialVerifier, byte[] clientNonce, byte[] serverNonce, int requestsPerMinute,
            int consoleRequestsPerMinute) {
        SessionCipher cipher = cipherFor(credentialVerifier, mode, clientNonce, serverNonce);
        return new RemoteSession(playerUuid, playerName, mode, clientId, List.of(cipher),
                List.of(credentialVerifier.clone()), requestsPerMinute, consoleRequestsPerMinute);
    }

    /**
     * Builds a pairing session that accepts any of the supplied pending-key verifiers.
     *
     * <p>An empty list yields a session with one random, unmatched cipher, so an unknown pairing
     * attempt fails exactly like a wrong key and cannot be told apart from one.
     */
    public static RemoteSession pairing(UUID playerUuid, String playerName, List<byte[]> pendingVerifiers,
            byte[] clientNonce, byte[] serverNonce, int requestsPerMinute, int consoleRequestsPerMinute) {
        List<SessionCipher> ciphers = new ArrayList<>();
        List<byte[]> verifiers = new ArrayList<>();
        for (byte[] verifier : pendingVerifiers) {
            ciphers.add(cipherFor(verifier, AuthMode.PAIRING_KEY, clientNonce, serverNonce));
            verifiers.add(verifier.clone());
        }
        if (ciphers.isEmpty()) {
            byte[] decoy = PasswordVault.randomNonce(32);
            ciphers.add(cipherFor(decoy, AuthMode.PAIRING_KEY, clientNonce, serverNonce));
            verifiers.add(decoy);
        }
        return new RemoteSession(playerUuid, playerName, AuthMode.PAIRING_KEY, "", ciphers, verifiers,
                requestsPerMinute, consoleRequestsPerMinute);
    }

    private static SessionCipher cipherFor(byte[] credentialVerifier, AuthMode mode, byte[] clientNonce,
            byte[] serverNonce) {
        byte[] copy = credentialVerifier.clone();
        byte[] sessionKey = Kdf.deriveSessionKey(copy, mode.id(), clientNonce.clone(), serverNonce.clone());
        return new SessionCipher(sessionKey);
    }

    public static byte[] newServerNonce() {
        return PasswordVault.randomNonce(HandshakeMessage.NONCE_LENGTH);
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String playerName() {
        return playerName;
    }

    public AuthMode authMode() {
        return authMode;
    }

    /** The client identity the handshake named; empty for the password and pairing modes. */
    public String claimedClientId() {
        return claimedClientId;
    }

    /**
     * The client identity that was actually proven, once a frame has decrypted in client-secret
     * mode. Empty until then, and empty in every other mode.
     */
    public String authenticatedClientId() {
        return activeCipher != null && authMode == AuthMode.CLIENT_SECRET ? claimedClientId : "";
    }

    public State state() {
        return state;
    }

    public boolean isAuthenticated() {
        return state == State.AUTHENTICATED;
    }

    /** Whether the first frame has decrypted, which proves possession of a candidate key. */
    public boolean isKeyProven() {
        return activeCipher != null;
    }

    /** Index of the candidate key that matched, or {@code -1} before a frame decrypts. */
    public int matchedIndex() {
        return matchedIndex;
    }

    /**
     * The verifier of the candidate key that matched, or {@code null} before a frame decrypts.
     *
     * <p>The caller uses this to consume the exact pending key that was proven, rather than relying
     * on a positional index that could drift if the registry changed between the handshake and the
     * pairing request.
     */
    public byte[] matchedVerifier() {
        int index = matchedIndex;
        if (index < 0 || index >= candidateVerifiers.size()) {
            return null;
        }
        return candidateVerifiers.get(index).clone();
    }

    public boolean isOwner() {
        return owner;
    }

    public void markOwner(boolean owner, int permissionLevel) {
        this.owner = owner;
        this.permissionLevel = permissionLevel;
    }

    public int permissionLevel() {
        return permissionLevel;
    }

    /** Records the permission level of the player at the time the session authenticated. */
    public void setPermissionLevel(int permissionLevel) {
        this.permissionLevel = permissionLevel;
    }

    public void markAuthenticated() {
        this.state = State.AUTHENTICATED;
        this.lastActivityMillis = System.currentTimeMillis();
    }

    /** Records activity so the idle-expiry check measures from the last real request. */
    public void touch() {
        this.lastActivityMillis = System.currentTimeMillis();
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public void close() {
        this.state = State.CLOSED;
    }

    /** Decrypts a frame after checking freshness and the rate budget. */
    public byte[] open(byte[] ciphertext, long nonce, long timestamp) throws SessionException {
        if (state == State.CLOSED) {
            throw new SessionException("session closed");
        }
        if (!replayGuard.accept(nonce, timestamp, System.currentTimeMillis())) {
            throw new SessionException("stale or replayed frame");
        }
        if (!generalLimiter.tryAcquire(System.currentTimeMillis())) {
            throw new SessionException("rate limit exceeded");
        }
        SessionCipher proven = activeCipher;
        if (proven != null) {
            return proven.decrypt(ciphertext, nonce, timestamp);
        }
        // First frame: find which candidate key the client actually holds. The one that
        // authenticates is remembered so later frames only try it.
        for (int i = 0; i < candidates.size(); i++) {
            try {
                byte[] plaintext = candidates.get(i).decrypt(ciphertext, nonce, timestamp);
                activeCipher = candidates.get(i);
                matchedIndex = i;
                return plaintext;
            } catch (SessionException ignored) {
                // Wrong candidate; try the next.
            }
        }
        throw new SessionException("message authentication failed");
    }

    /**
     * Consumes one console budget slot.
     *
     * <p>The general limiter in {@link #open} runs before decryption, so it cannot know what the
     * frame contains. Console commands are the expensive orders, so they are also charged against
     * the stricter console budget once the order type has been read.
     *
     * @return {@code true} when the console budget had a slot free
     */
    public boolean acquireConsole() {
        return consoleLimiter.tryAcquire(System.currentTimeMillis());
    }

    /** Encrypts an outgoing frame, returning the sealed envelope. */
    public com.adminmod.common.net.Envelope seal(byte[] plaintext, long nonce, long timestamp) {
        SessionCipher cipher = activeCipher;
        if (cipher == null) {
            // The caller only seals after open() proved a key, so this is a programming error.
            throw new IllegalStateException("no key has been proven for this session");
        }
        return new com.adminmod.common.net.Envelope(nonce, timestamp, cipher.encrypt(plaintext, nonce, timestamp));
    }

    /** Non-secret key fingerprint, reported in the handshake result for diagnostics. */
    public String fingerprint() {
        SessionCipher cipher = activeCipher;
        return cipher == null ? "" : cipher.fingerprint();
    }
}
