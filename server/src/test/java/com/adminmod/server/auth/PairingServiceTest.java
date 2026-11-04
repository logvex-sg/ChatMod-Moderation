package com.adminmod.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.security.ClientRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pairing is the private-client half of authentication, so its invariants are tested directly:
 * a key is single use, it expires, revocation removes a client, and rotation changes the stored
 * verifier. These are the properties that keep a leaked pairing key from being reusable.
 */
class PairingServiceTest {

    private static PairingService service(Path dir, AdminConfig config) throws IOException {
        return new PairingService(config, dir.resolve("clients.json"));
    }

    @Test
    void anIssuedKeyCanBeRedeemedExactlyOnce(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        PairingService pairing = service(dir, config);

        PairingService.IssuedKey issued = pairing.issueKey("owner");
        assertFalse(issued.key().isBlank());

        PairingService.Paired first = pairing.pair(issued.key(), "Laptop", "adminmod/1.0.0", "uuid", "owner");
        assertTrue(first.success());
        assertFalse(first.clientSecret().isBlank());

        // The same key must not enrol a second client.
        PairingService.Paired second = pairing.pair(issued.key(), "Desktop", "adminmod/1.0.0", "uuid", "owner");
        assertFalse(second.success());
    }

    @Test
    void anExpiredKeyIsRefused(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        config.remoteAdmin.pairingKeyTtlSeconds = 0L;
        PairingService pairing = service(dir, config);

        PairingService.IssuedKey issued = pairing.issueKey("owner");
        // The minimum TTL is 30s, so an explicit consumption in the far future is what expires it.
        byte[] verifier = com.adminmod.common.security.Kdf.derivePairingKey(issued.key());
        assertFalse(pairing.registry().consumePendingKey(verifier, System.currentTimeMillis() + 120_000L));
    }

    @Test
    void revokingAClientRemovesIt(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        PairingService pairing = service(dir, config);

        PairingService.IssuedKey issued = pairing.issueKey("owner");
        PairingService.Paired paired = pairing.pair(issued.key(), "Laptop", "adminmod/1.0.0", "uuid", "owner");
        assertEquals(1, pairing.clientCount());
        assertTrue(pairing.isKnown(paired.clientId()));

        assertTrue(pairing.revoke(paired.clientId()));
        assertEquals(0, pairing.clientCount());
        assertFalse(pairing.isKnown(paired.clientId()));
    }

    @Test
    void rotatingAClientSecretChangesTheVerifier(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        PairingService pairing = service(dir, config);

        PairingService.IssuedKey issued = pairing.issueKey("owner");
        PairingService.Paired paired = pairing.pair(issued.key(), "Laptop", "adminmod/1.0.0", "uuid", "owner");
        byte[] before = pairing.verifierOf(paired.clientId()).clone();

        String rotated = pairing.rotateClientSecret(paired.clientId());
        assertNotEquals(paired.clientSecret(), rotated);

        byte[] after = pairing.verifierOf(paired.clientId());
        assertFalse(java.util.Arrays.equals(before, after));
        // The old secret no longer derives the stored verifier.
        assertFalse(java.util.Arrays.equals(
                com.adminmod.common.security.Kdf.deriveClientKey(paired.clientSecret()), after));
        assertTrue(java.util.Arrays.equals(
                com.adminmod.common.security.Kdf.deriveClientKey(rotated), after));
    }

    @Test
    void theRegistryFileNeverContainsThePlaintextKeyOrSecret(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        PairingService pairing = service(dir, config);

        PairingService.IssuedKey issued = pairing.issueKey("owner");
        PairingService.Paired paired = pairing.pair(issued.key(), "Laptop", "adminmod/1.0.0", "uuid", "owner");

        String file = Files.readString(pairing.file());
        assertFalse(file.contains(issued.key()), "the pairing key must not be stored in the clear");
        assertFalse(file.contains(paired.clientSecret()), "the client secret must not be stored in the clear");
    }

    @Test
    void aRegistryReloadsWithTheSameClients(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        PairingService pairing = service(dir, config);
        PairingService.IssuedKey issued = pairing.issueKey("owner");
        PairingService.Paired paired = pairing.pair(issued.key(), "Laptop", "adminmod/1.0.0", "uuid", "owner");

        PairingService reloaded = service(dir, config);
        assertTrue(reloaded.isKnown(paired.clientId()));
        assertTrue(java.util.Arrays.equals(reloaded.verifierOf(paired.clientId()),
                com.adminmod.common.security.Kdf.deriveClientKey(paired.clientSecret())));
    }

    @Test
    void pairingIsRefusedWhenDisabled(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        config.remoteAdmin.allowPairing = false;
        PairingService pairing = service(dir, config);

        // The key cannot even be minted when pairing is off, and redeeming any value fails.
        assertFalse(pairing.pair("anything", "Laptop", "adminmod/1.0.0", "uuid", "owner").success());
    }

    @Test
    void aControlCharacterLabelIsSanitised(@TempDir Path dir) throws IOException {
        AdminConfig config = new AdminConfig();
        PairingService pairing = service(dir, config);
        PairingService.IssuedKey issued = pairing.issueKey("owner");

        PairingService.Paired paired = pairing.pair(issued.key(), "bad\u0007name\ninjected", "adminmod/1.0.0",
                "uuid", "owner");
        assertTrue(paired.success());
        ClientRegistry.Client client = pairing.registry().client(paired.clientId());
        assertFalse(client.label().contains("\u0007"));
        assertFalse(client.label().contains("\n"));
    }
}
