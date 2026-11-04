package com.adminmod.server.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.adminmod.common.net.Message;
import com.adminmod.common.net.OrderMessage;
import com.adminmod.common.net.OrderType;
import com.adminmod.common.security.Kdf;
import com.adminmod.common.security.PasswordVault;
import com.adminmod.common.security.SessionCipher;
import com.adminmod.common.security.SessionException;

import org.junit.jupiter.api.Test;

/**
 * Session-level protections: replay rejection, the general rate budget, and the stricter console
 * budget. These are the checks that stand between a connected player and a remote order, so they
 * are exercised directly rather than only through a running server.
 */
class RemoteSessionTest {

    private static final UUID PLAYER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** A server session plus the matching client-side cipher, as the two halves of a handshake. */
    private record Pair(RemoteSession session, SessionCipher client) {
    }

    private static Pair handshake(int generalLimit, int consoleLimit) {
        PasswordVault vault = PasswordVault.create().vault();
        byte[] clientNonce = PasswordVault.randomNonce(16);
        byte[] serverNonce = PasswordVault.randomNonce(16);
        byte[] key = Kdf.deriveSessionKey(vault.verifierCopy(), clientNonce, serverNonce);
        RemoteSession session = new RemoteSession(PLAYER, "Alex", clientNonce, serverNonce, vault,
                generalLimit, consoleLimit);
        return new Pair(session, new SessionCipher(key));
    }

    private static byte[] sealedOrder(SessionCipher client, long nonce, long timestamp, OrderType type) {
        byte[] body = Message.encode(new OrderMessage(1, type, "arg"));
        return client.encrypt(body, nonce, timestamp);
    }

    @Test
    void aFreshFrameOpensAndRoundTrips() throws Exception {
        Pair pair = handshake(60, 10);
        long nonce = 42L;
        long timestamp = System.currentTimeMillis();
        byte[] ciphertext = sealedOrder(pair.client(), nonce, timestamp, OrderType.STATUS);

        byte[] plaintext = pair.session().open(ciphertext, nonce, timestamp);
        assertArrayEquals(Message.encode(new OrderMessage(1, OrderType.STATUS, "arg")), plaintext);
    }

    @Test
    void aReplayedNonceIsRejected() throws Exception {
        Pair pair = handshake(60, 10);
        long nonce = 7L;
        long timestamp = System.currentTimeMillis();
        byte[] ciphertext = sealedOrder(pair.client(), nonce, timestamp, OrderType.STATUS);

        pair.session().open(ciphertext, nonce, timestamp);
        assertThrows(SessionException.class, () -> pair.session().open(ciphertext, nonce, timestamp),
                "the same nonce must not be accepted twice");
    }

    @Test
    void aStaleTimestampIsRejected() {
        Pair pair = handshake(60, 10);
        long nonce = 9L;
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        byte[] ciphertext = sealedOrder(pair.client(), nonce, stale, OrderType.STATUS);

        assertThrows(SessionException.class, () -> pair.session().open(ciphertext, nonce, stale));
    }

    @Test
    void theGeneralBudgetIsExhaustedAfterTheLimit() throws Exception {
        Pair pair = handshake(3, 10);
        long base = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            long nonce = 100L + i;
            pair.session().open(sealedOrder(pair.client(), nonce, base, OrderType.STATUS), nonce, base);
        }
        long nonce = 200L;
        byte[] ciphertext = sealedOrder(pair.client(), nonce, base, OrderType.STATUS);
        assertThrows(SessionException.class, () -> pair.session().open(ciphertext, nonce, base));
    }

    @Test
    void theConsoleBudgetIsSeparateFromTheGeneralBudget() throws Exception {
        Pair pair = handshake(60, 2);

        assertTrue(pair.session().acquireConsole());
        assertTrue(pair.session().acquireConsole());
        assertFalse(pair.session().acquireConsole(), "the third console command is over budget");
        // The general budget is untouched by the console charges.
        long nonce = 55L;
        long timestamp = System.currentTimeMillis();
        pair.session().open(sealedOrder(pair.client(), nonce, timestamp, OrderType.STATUS), nonce, timestamp);
    }

    @Test
    void aClosedSessionAcceptsNothing() throws Exception {
        Pair pair = handshake(60, 10);
        pair.session().close();
        long nonce = 5L;
        long timestamp = System.currentTimeMillis();
        byte[] ciphertext = sealedOrder(pair.client(), nonce, timestamp, OrderType.STATUS);
        assertThrows(SessionException.class, () -> pair.session().open(ciphertext, nonce, timestamp));
    }

    @Test
    void aTamperedFrameFailsToDecrypt() {
        Pair pair = handshake(60, 10);
        long nonce = 11L;
        long timestamp = System.currentTimeMillis();
        byte[] ciphertext = sealedOrder(pair.client(), nonce, timestamp, OrderType.STATUS);
        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThrows(SessionException.class, () -> pair.session().open(ciphertext, nonce, timestamp));
    }

    @Test
    void permissionStateIsRecordedOnTheSession() {
        Pair pair = handshake(60, 10);
        assertFalse(pair.session().isAuthenticated());
        assertEquals(0, pair.session().permissionLevel());

        pair.session().markAuthenticated();
        pair.session().markOwner(true, 4);

        assertTrue(pair.session().isAuthenticated());
        assertTrue(pair.session().isOwner());
        assertEquals(4, pair.session().permissionLevel());
    }
}
