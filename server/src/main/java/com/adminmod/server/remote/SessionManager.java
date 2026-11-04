package com.adminmod.server.remote;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.net.AdminProtocol;
import com.adminmod.common.net.AuthMode;
import com.adminmod.common.net.Envelope;
import com.adminmod.common.net.HandshakeMessage;
import com.adminmod.common.net.HandshakeResultMessage;
import com.adminmod.common.net.Message;
import com.adminmod.common.net.OrderMessage;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.net.OrderType;
import com.adminmod.common.net.PairingRequestMessage;
import com.adminmod.common.net.PairingResultMessage;
import com.adminmod.common.security.Kdf;
import com.adminmod.common.security.SessionException;
import com.adminmod.server.auth.AuthHistory;
import com.adminmod.server.auth.AuthThrottle;
import com.adminmod.server.auth.CredentialService;
import com.adminmod.server.auth.PairingService;
import com.adminmod.server.auth.PermissionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Tracks one {@link RemoteSession} per connected player and moves frames between the wire and the
 * request handler.
 *
 * <p>The exchange has three steps. The client sends a plaintext handshake announcing its protocol
 * version, build id, chosen authentication mode and a random nonce. The server answers with its own
 * nonce and a status, still plaintext because neither side has agreed on a key yet and the answer
 * carries no secret. Both sides then mix the two nonces with the credential (the operator password,
 * a one-time pairing key, or a paired client's secret) to obtain the session key. From that point on
 * every frame is sealed with AES-GCM, so the first successfully decrypted frame is the proof that
 * the client holds the credential.
 *
 * <p>Because decryption failure is indistinguishable from a wrong credential, a failed frame closes
 * the session outright rather than allowing further attempts against the same key, and repeated
 * failures lock the peer address out for a while.
 */
public final class SessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("adminmod");

    private final Map<UUID, RemoteSession> sessions = new ConcurrentHashMap<>();
    private final CredentialService credentials;
    private final AdminConfig config;
    private final PermissionService permissions;
    private final RemoteRequestHandler handler;
    private final PairingService pairing;
    private final AuthThrottle throttle;
    private final AuthHistory history;
    private final String serverVersion;

    public SessionManager(CredentialService credentials, AdminConfig config, PermissionService permissions,
            RemoteRequestHandler handler, PairingService pairing, String serverVersion) {
        this.credentials = credentials;
        this.config = config;
        this.permissions = permissions;
        this.handler = handler;
        this.pairing = pairing;
        this.serverVersion = serverVersion;
        this.throttle = new AuthThrottle(config.remoteAdmin.authFailureLimit,
                config.remoteAdmin.authLockoutSeconds * 1000L);
        this.history = new AuthHistory(config.remoteAdmin.authHistoryLimit);
    }

    public RemoteSession session(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    public boolean hasSession(UUID playerUuid) {
        RemoteSession session = sessions.get(playerUuid);
        return session != null && session.isAuthenticated();
    }

    public AuthHistory history() {
        return history;
    }

    public void close(UUID playerUuid) {
        RemoteSession session = sessions.remove(playerUuid);
        if (session != null) {
            session.close();
        }
    }

    /** Closes every session. Called on server shutdown so no state outlives the world. */
    public void closeAll() {
        for (RemoteSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    /**
     * Drops sessions that have been idle for longer than the configured lifetime.
     *
     * <p>A forgotten client keeps a decrypted key alive for as long as the player stays connected;
     * expiring idle sessions bounds how long a leaked key remains usable. Called from the server
     * tick, so it only walks the (small) session map.
     */
    public int expireIdle(long now) {
        long lifetime = Math.max(1, config.remoteAdmin.sessionTimeoutSeconds) * 1000L;
        int expired = 0;
        for (Map.Entry<UUID, RemoteSession> entry : sessions.entrySet()) {
            RemoteSession session = entry.getValue();
            if (session.isAuthenticated() && now - session.lastActivityMillis() > lifetime) {
                if (sessions.remove(entry.getKey(), session)) {
                    session.close();
                    history.record(session.playerName(), "session-expired", true, "idle timeout");
                    expired++;
                }
            }
        }
        throttle.prune(now);
        return expired;
    }

    public int authenticatedSessions() {
        int count = 0;
        for (RemoteSession session : sessions.values()) {
            if (session.isAuthenticated()) {
                count++;
            }
        }
        return count;
    }

    /** Live, non-secret view of the sessions for the dashboard. */
    public List<RemoteSession> activeSessions() {
        return List.copyOf(sessions.values());
    }

    /**
     * Handles one inbound frame.
     *
     * @return the answer frame to send back, or {@code null} when the frame must be ignored
     */
    public byte[] handleFrame(ServerPlayerEntity player, byte[] frame) {
        Envelope envelope;
        try {
            envelope = Envelope.decode(frame);
        } catch (RuntimeException e) {
            return null;
        }

        Message message;
        try {
            message = Message.decode(envelope.body());
        } catch (RuntimeException e) {
            message = null;
        }

        // A handshake is always plaintext, because it is what establishes the key.
        if (message instanceof HandshakeMessage handshake) {
            return handleHandshake(player, handshake);
        }

        RemoteSession session = sessions.get(player.getUuid());
        if (session == null || session.state() == RemoteSession.State.CLOSED) {
            return null;
        }

        byte[] plaintext;
        try {
            plaintext = session.open(envelope.body(), envelope.nonce(), envelope.timestamp());
        } catch (SessionException e) {
            onFrameAuthenticationFailure(player, session);
            return null;
        }

        Message request;
        try {
            request = Message.decode(plaintext);
        } catch (RuntimeException e) {
            return null;
        }

        // The pairing request is answered with the key that just decrypted; it never reaches the
        // order path, because it must be handled before the session is treated as authenticated.
        if (request instanceof PairingRequestMessage pairingRequest) {
            return seal(session, handlePairingRequest(player, session, pairingRequest));
        }

        if (!(request instanceof OrderMessage order)) {
            return null;
        }

        // Console commands are the expensive orders, so they are charged against the stricter
        // console budget as well as the general one already consumed by open().
        if (order.orderType() == OrderType.EXECUTE_COMMAND && !session.acquireConsole()) {
            return seal(session, new OrderResultMessage(order.requestId(), false, "console rate limit exceeded"));
        }

        // The key is proven by the successful decryption above.
        session.markAuthenticated();
        session.touch();
        String address = addressOf(player);
        throttle.recordSuccess(address);

        int level = handler.permissionLevelOf(player);
        boolean owner = permissions.isOwner(player.getUuid());
        if (!owner && permissions.claimOwnershipIfVacant(player.getUuid(), level)) {
            owner = true;
            // The first operator to connect becomes the owner; that choice is written back so it
            // survives a restart and cannot be taken over by a later connection.
            handler.persistConfig();
        }
        level = permissions.effectiveLevel(player.getUuid(), player.getGameProfile().getName(), level);
        session.setPermissionLevel(level);
        session.markOwner(owner, level);

        PermissionService.Role role = permissions.role(player.getUuid(), level);
        if (!permissions.allows(order.orderType(), role)) {
            history.record(player.getGameProfile().getName(), "denied:" + order.orderType(),
                    false, "role " + role);
            return seal(session, new OrderResultMessage(order.requestId(), false, "permission denied"));
        }

        OrderResultMessage result = handler.handle(order, player.getUuid(), player.getGameProfile().getName(), owner);
        return seal(session, result);
    }

    private byte[] handleHandshake(ServerPlayerEntity player, HandshakeMessage handshake) {
        byte[] serverNonce = RemoteSession.newServerNonce();
        String name = player.getGameProfile().getName();
        String address = addressOf(player);
        long now = System.currentTimeMillis();

        if (handshake.protocolVersion() != AdminProtocol.PROTOCOL_VERSION) {
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_VERSION_MISMATCH,
                    allowedModes()));
        }
        if (!config.remoteAdmin.enabled || credentials == null) {
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_DISABLED, allowedModes()));
        }
        if (!buildAllowed(handshake.buildId())) {
            history.record(name, "handshake", false, "build rejected: " + handshake.buildId());
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_BUILD_REJECTED,
                    allowedModes()));
        }
        if (throttle.isLockedOut(address, now)) {
            history.record(name, "handshake", false, "locked out");
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_LOCKED_OUT, allowedModes()));
        }

        AuthMode mode;
        try {
            mode = handshake.mode();
        } catch (RuntimeException e) {
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_REJECTED, allowedModes()));
        }
        if ((allowedModes() & (1 << mode.id())) == 0) {
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_MODE_NOT_ALLOWED,
                    allowedModes()));
        }

        RemoteSession session = buildSession(player, handshake, mode, serverNonce);
        if (session == null) {
            history.record(name, "handshake", false, "unknown client " + handshake.clientId());
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_UNKNOWN_CLIENT,
                    allowedModes()));
        }

        RemoteSession previous = sessions.put(player.getUuid(), session);
        if (previous != null) {
            previous.close();
        }

        // The password is only ever disclosed to a player who already holds operator rights and
        // only when the operator opted in. It cannot be used to gain privileges: the recipient can
        // already run every console command.
        String credential = "";
        if (config.remoteAdmin.revealPasswordToOperators
                && player.getServer() != null
                && player.getServer().getPlayerManager().isOperator(player.getGameProfile())) {
            credential = credentials.password();
        }

        history.record(name, "handshake", true, mode.name() + " from " + address);
        return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                AdminProtocol.BUILD_ID, serverNonce, HandshakeResultMessage.STATUS_OK, allowedModes(), credential));
    }

    /** Builds the session for the chosen mode, or {@code null} when the credential is unknown. */
    private RemoteSession buildSession(ServerPlayerEntity player, HandshakeMessage handshake, AuthMode mode,
            byte[] serverNonce) {
        int rpm = config.remoteAdmin.requestsPerMinute;
        int consoleRpm = config.remoteAdmin.consoleRequestsPerMinute;
        return switch (mode) {
            case PASSWORD -> RemoteSession.single(player.getUuid(), player.getGameProfile().getName(),
                    AuthMode.PASSWORD, "", credentials.vault().verifierCopy(), handshake.clientNonce(),
                    serverNonce, rpm, consoleRpm);
            case PAIRING_KEY -> RemoteSession.pairing(player.getUuid(), player.getGameProfile().getName(),
                    pairing.pendingVerifiers(), handshake.clientNonce(), serverNonce, rpm, consoleRpm);
            case CLIENT_SECRET -> {
                byte[] verifier = pairing.verifierOf(handshake.clientId());
                if (verifier == null || verifier.length == 0) {
                    yield null;
                }
                yield RemoteSession.single(player.getUuid(), player.getGameProfile().getName(),
                        AuthMode.CLIENT_SECRET, handshake.clientId(), verifier, handshake.clientNonce(),
                        serverNonce, rpm, consoleRpm);
            }
        };
    }

    /**
     * Answers a pairing request, sealing the reply with the key that decrypted it.
     *
     * <p>Only a pairing-mode session may pair. The client label and build id are treated as
     * untrusted strings; the identity is bound to the connecting player's UUID so a paired client
     * cannot later be used by a different account without re-pairing.
     */
    private PairingResultMessage handlePairingRequest(ServerPlayerEntity player, RemoteSession session,
            PairingRequestMessage request) {
        if (session.authMode() != AuthMode.PAIRING_KEY) {
            history.record(player.getGameProfile().getName(), "pairing", false, "not a pairing session");
            return new PairingResultMessage(request.requestId(), false, "this session is not a pairing session");
        }
        if (!session.isKeyProven()) {
            return new PairingResultMessage(request.requestId(), false, "authentication failed");
        }
        String name = player.getGameProfile().getName();
        String uuid = player.getUuid().toString();
        try {
            // The session was opened against the pending keys; the one that decrypted is the key
            // that is about to be consumed, so consume by re-deriving from the matched candidate.
            PairingService.Paired paired = pairing.pairFromMatched(session.matchedVerifier(),
                    request.label(), request.clientBuildId(), uuid, name);
            if (!paired.success()) {
                history.record(name, "pairing", false, paired.message());
                return new PairingResultMessage(request.requestId(), false, paired.message());
            }
            history.record(name, "pairing", true, "client " + paired.clientId() + " paired");
            return new PairingResultMessage(request.requestId(), true, paired.message(), paired.clientId(),
                    paired.clientSecret());
        } catch (IOException e) {
            LOGGER.warn("Could not persist a paired client", e);
            return new PairingResultMessage(request.requestId(), false, "the client could not be persisted");
        }
    }

    /**
     * Records a failed first-frame decryption and, once the limit is reached, locks the address out.
     *
     * <p>A frame that fails to authenticate is the only signal that a credential was wrong, so this
     * is where the failure counter is advanced.
     */
    private void onFrameAuthenticationFailure(ServerPlayerEntity player, RemoteSession session) {
        String address = addressOf(player);
        long now = System.currentTimeMillis();
        throttle.recordFailure(address, now);
        history.record(player.getGameProfile().getName(),
                session.isKeyProven() ? "frame" : "authentication", false, "authentication failed");
        sessions.remove(player.getUuid(), session);
        session.close();
    }

    private int allowedModes() {
        int mask = 0;
        if (config.remoteAdmin.allowPasswordAuth && !config.remoteAdmin.requirePairedClient) {
            mask |= 1 << AuthMode.PASSWORD.id();
        }
        // Pairing and client-secret authentication both need the registry; without it neither mode
        // is advertised, so a client is never told it may use a path the server cannot serve.
        if (pairing != null) {
            if (config.remoteAdmin.allowPairing) {
                mask |= 1 << AuthMode.PAIRING_KEY.id();
            }
            mask |= 1 << AuthMode.CLIENT_SECRET.id();
        }
        return mask;
    }

    private boolean buildAllowed(String buildId) {
        List<String> allowed = config.remoteAdmin.allowedClientBuilds;
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (buildId == null || buildId.isBlank()) {
            return false;
        }
        for (String candidate : allowed) {
            if (candidate != null && candidate.equals(buildId)) {
                return true;
            }
        }
        return false;
    }

    private static String addressOf(ServerPlayerEntity player) {
        if (player.networkHandler == null) {
            return "unknown";
        }
        try {
            String address = player.networkHandler.getConnectionAddress() == null ? null
                    : player.networkHandler.getConnectionAddress().toString();
            return address == null ? "unknown" : address;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * Wraps a handshake result in a plain envelope. The result carries only a nonce and a status,
     * so there is nothing here that needs protecting.
     */
    private byte[] plaintextResult(HandshakeResultMessage result) {
        return new Envelope(0L, System.currentTimeMillis(), Message.encode(result)).encode();
    }

    private byte[] seal(RemoteSession session, OrderResultMessage result) {
        long nonce = ThreadLocalRandom.current().nextLong();
        return session.seal(Message.encode(result), nonce, System.currentTimeMillis()).encode();
    }

    private byte[] seal(RemoteSession session, PairingResultMessage result) {
        long nonce = ThreadLocalRandom.current().nextLong();
        return session.seal(Message.encode(result), nonce, System.currentTimeMillis()).encode();
    }
}
