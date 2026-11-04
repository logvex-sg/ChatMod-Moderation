package com.adminmod.server.remote;

import java.util.UUID;

import com.adminmod.common.net.HandshakeMessage;
import com.adminmod.common.security.Kdf;
import com.adminmod.common.security.PasswordVault;
import com.adminmod.common.security.ReplayGuard;
import com.adminmod.common.security.SessionCipher;
import com.adminmod.common.security.SessionException;
import com.adminmod.common.security.SlidingWindowRateLimiter;

/**
 * Server-side state for one authenticated remote administration connection.
 *
 * <p>A session is created in the unauthenticated state when the client sends its handshake, and
 * only becomes usable once the client proves knowledge of the operator password by sending a
 * correctly authenticated first frame. The session key mixes both handshake nonces, so the
 * password itself never travels over the wire.
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
    private final byte[] clientNonce;
    private final byte[] serverNonce;
    private final SessionCipher cipher;
    private final ReplayGuard replayGuard = new ReplayGuard();
    private final SlidingWindowRateLimiter generalLimiter;
    private final SlidingWindowRateLimiter consoleLimiter;

    private volatile State state = State.PENDING;
    private volatile boolean owner;
    private volatile int permissionLevel;
    private volatile long lastActivityMillis = System.currentTimeMillis();

    public RemoteSession(UUID playerUuid, String playerName, byte[] clientNonce, byte[] serverNonce,
            PasswordVault vault, int requestsPerMinute, int consoleRequestsPerMinute) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.clientNonce = clientNonce.clone();
        this.serverNonce = serverNonce.clone();
        byte[] passwordKey = vault.verifierCopy();
        byte[] sessionKey = Kdf.deriveSessionKey(passwordKey, this.clientNonce, this.serverNonce);
        this.cipher = new SessionCipher(sessionKey);
        this.generalLimiter = new SlidingWindowRateLimiter(requestsPerMinute, 60_000L);
        this.consoleLimiter = new SlidingWindowRateLimiter(consoleRequestsPerMinute, 60_000L);
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

    public State state() {
        return state;
    }

    public boolean isAuthenticated() {
        return state == State.AUTHENTICATED;
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
        return cipher.decrypt(ciphertext, nonce, timestamp);
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
        return new com.adminmod.common.net.Envelope(nonce, timestamp, cipher.encrypt(plaintext, nonce, timestamp));
    }

    /** Non-secret key fingerprint, reported in the handshake result for diagnostics. */
    public String fingerprint() {
        return cipher.fingerprint();
    }
}
