package com.adminmod.server.remote;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.net.AdminProtocol;
import com.adminmod.common.net.Envelope;
import com.adminmod.common.net.HandshakeMessage;
import com.adminmod.common.net.HandshakeResultMessage;
import com.adminmod.common.net.Message;
import com.adminmod.common.net.OrderMessage;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.security.SessionException;
import com.adminmod.server.auth.CredentialService;
import com.adminmod.server.auth.PermissionService;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Tracks one {@link RemoteSession} per connected player and moves frames between the wire and the
 * request handler.
 *
 * <p>The exchange has three steps. The client sends a plaintext handshake announcing its protocol
 * version and a random nonce. The server answers with its own nonce and a status, still plaintext
 * because neither side has agreed on a key yet and the answer carries no secret. Both sides then
 * mix the two nonces with the operator password to obtain the session key. From that point on
 * every frame is sealed with AES-GCM, so the first successfully decrypted frame is the proof that
 * the client knows the password.
 *
 * <p>Because decryption failure is indistinguishable from a wrong password, a failed frame closes
 * the session outright rather than allowing further attempts against the same key.
 */
public final class SessionManager {

    private final Map<UUID, RemoteSession> sessions = new ConcurrentHashMap<>();
    private final CredentialService credentials;
    private final AdminConfig config;
    private final PermissionService permissions;
    private final RemoteRequestHandler handler;
    private final String serverVersion;

    public SessionManager(CredentialService credentials, AdminConfig config, PermissionService permissions,
            RemoteRequestHandler handler, String serverVersion) {
        this.credentials = credentials;
        this.config = config;
        this.permissions = permissions;
        this.handler = handler;
        this.serverVersion = serverVersion;
    }

    public RemoteSession session(UUID playerUuid) {
        return sessions.get(playerUuid);
    }

    public boolean hasSession(UUID playerUuid) {
        RemoteSession session = sessions.get(playerUuid);
        return session != null && session.isAuthenticated();
    }

    public void close(UUID playerUuid) {
        RemoteSession session = sessions.remove(playerUuid);
        if (session != null) {
            session.close();
        }
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
            plaintext = session.open(envelope.body(), envelope.nonce(), envelope.timestamp(), false);
        } catch (SessionException e) {
            sessions.remove(player.getUuid());
            session.close();
            return null;
        }

        Message request;
        try {
            request = Message.decode(plaintext);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(request instanceof OrderMessage order)) {
            return null;
        }

        // The key is proven by the successful decryption above.
        session.markAuthenticated();

        int level = handler.permissionLevelOf(player);
        boolean owner = permissions.isOwner(player.getUuid());
        if (!owner && permissions.claimOwnershipIfVacant(player.getUuid(), level)) {
            owner = true;
            // The first operator to connect becomes the owner; that choice is written back so it
            // survives a restart and cannot be taken over by a later connection.
            handler.persistConfig();
        }
        session.setPermissionLevel(level);
        session.markOwner(owner, level);

        if (!permissions.allows(order.orderType(), level, owner)) {
            return seal(session, new OrderResultMessage(order.requestId(), false, "permission denied"));
        }

        OrderResultMessage result = handler.handle(order, player.getUuid(), player.getGameProfile().getName(), owner);
        return seal(session, result);
    }

    private byte[] handleHandshake(ServerPlayerEntity player, HandshakeMessage handshake) {
        byte[] serverNonce = RemoteSession.newServerNonce();

        if (handshake.protocolVersion() != AdminProtocol.PROTOCOL_VERSION) {
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    serverNonce, HandshakeResultMessage.STATUS_VERSION_MISMATCH));
        }
        if (!config.remoteAdmin.enabled) {
            return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion,
                    serverNonce, HandshakeResultMessage.STATUS_DISABLED));
        }

        RemoteSession previous = sessions.put(player.getUuid(),
                new RemoteSession(player.getUuid(), player.getGameProfile().getName(), handshake.clientNonce(),
                        serverNonce, credentials.vault(), config.remoteAdmin.requestsPerMinute,
                        config.remoteAdmin.consoleRequestsPerMinute));
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

        return plaintextResult(new HandshakeResultMessage(AdminProtocol.PROTOCOL_VERSION, serverVersion, serverNonce,
                HandshakeResultMessage.STATUS_OK, credential));
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
}
