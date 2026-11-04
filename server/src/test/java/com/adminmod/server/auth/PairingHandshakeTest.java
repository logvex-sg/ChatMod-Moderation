package com.adminmod.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.net.AuthMode;
import com.adminmod.common.net.Message;
import com.adminmod.common.net.OrderMessage;
import com.adminmod.common.net.OrderType;
import com.adminmod.common.net.PairingRequestMessage;
import com.adminmod.common.security.Kdf;
import com.adminmod.common.security.PasswordVault;
import com.adminmod.common.security.SessionCipher;
import com.adminmod.server.remote.RemoteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Walks the whole pairing exchange at the cryptographic level, with the client half simulated
 * exactly as {@code AdminClientNetworking} performs it.
 *
 * <p>This is the test that would catch a mismatch between the salt a mode uses on each side, or a
 * candidate key that does not line up with the verifier the registry stored. Those are silent
 * failures in production - a handshake that simply never authenticates - so they are pinned here.
 */
class PairingHandshakeTest {

    private static final UUID PLAYER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void aPairingKeyEnrolsAClientThatCanThenAuthenticate(@TempDir Path dir) throws Exception {
        AdminConfig config = new AdminConfig();
        PairingService pairing = new PairingService(config, dir.resolve("clients.json"));

        // --- Pairing: the operator mints a key, the client redeems it. -------------------------
        PairingService.IssuedKey issued = pairing.issueKey("owner");

        byte[] clientNonce = PasswordVault.randomNonce(16);
        byte[] serverNonce = PasswordVault.randomNonce(16);
        // The client derives the pairing verifier and mixes it with the nonces, exactly as the
        // client networking does for AuthMode.PAIRING_KEY.
        byte[] clientVerifier = Kdf.derivePairingKey(issued.key());
        byte[] clientSessionKey = Kdf.deriveSessionKey(clientVerifier.clone(), AuthMode.PAIRING_KEY.id(),
                clientNonce, serverNonce);
        SessionCipher clientCipher = new SessionCipher(clientSessionKey);

        RemoteSession pairingSession = RemoteSession.pairing(PLAYER, "Alex", pairing.pendingVerifiers(),
                clientNonce, serverNonce, 60, 10);

        long nonce = 1L;
        long timestamp = System.currentTimeMillis();
        byte[] request = Message.encode(new PairingRequestMessage(1, "Alex@localhost", "adminmod/1.0.0"));
        byte[] sealed = clientCipher.encrypt(request, nonce, timestamp);

        byte[] plaintext = pairingSession.open(sealed, nonce, timestamp);
        assertTrue(pairingSession.isKeyProven());
        assertNotNull(pairingSession.matchedVerifier());
        assertEquals(Message.decode(plaintext) instanceof PairingRequestMessage, true);

        PairingService.Paired paired = pairing.pairFromMatched(pairingSession.matchedVerifier(), "Alex@localhost",
                "adminmod/1.0.0", PLAYER.toString(), "Alex");
        assertTrue(paired.success());
        assertTrue(pairing.isKnown(paired.clientId()));

        // --- Re-authentication: the client now uses the issued secret. -------------------------
        byte[] clientNonce2 = PasswordVault.randomNonce(16);
        byte[] serverNonce2 = PasswordVault.randomNonce(16);
        byte[] secretVerifier = Kdf.deriveClientKey(paired.clientSecret());
        byte[] clientSessionKey2 = Kdf.deriveSessionKey(secretVerifier.clone(), AuthMode.CLIENT_SECRET.id(),
                clientNonce2, serverNonce2);
        SessionCipher clientCipher2 = new SessionCipher(clientSessionKey2);

        // The server looks the verifier up by the client id the handshake named.
        RemoteSession secretSession = RemoteSession.single(PLAYER, "Alex", AuthMode.CLIENT_SECRET,
                paired.clientId(), pairing.verifierOf(paired.clientId()), clientNonce2, serverNonce2, 60, 10);

        long nonce2 = 2L;
        long timestamp2 = System.currentTimeMillis();
        byte[] order = Message.encode(new OrderMessage(2, OrderType.STATUS, "arg"));
        byte[] sealed2 = clientCipher2.encrypt(order, nonce2, timestamp2);

        byte[] opened = secretSession.open(sealed2, nonce2, timestamp2);
        assertTrue(secretSession.isKeyProven());
        assertEquals(paired.clientId(), secretSession.authenticatedClientId());
        assertTrue(Message.decode(opened) instanceof OrderMessage);
    }

    @Test
    void aRevokedClientSecretNoLongerAuthenticates(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        PairingService pairing = new PairingService(config, dir.resolve("clients.json"));

        PairingService.IssuedKey issued = pairing.issueKey("owner");
        PairingService.Paired paired = pairing.pair(issued.key(), "Laptop", "adminmod/1.0.0", "uuid", "owner");
        assertTrue(pairing.revoke(paired.clientId()));

        // After revocation the verifier is gone, so the server cannot build a session for the id.
        org.junit.jupiter.api.Assertions.assertNull(pairing.verifierOf(paired.clientId()));
    }
}
