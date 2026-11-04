package com.adminmod.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry is the durable half of pairing. Its job is to hold only verifiers, to consume a
 * pending key exactly once, and to survive a reload. Each of those is checked here.
 */
class ClientRegistryTest {

    @Test
    void aPendingKeyIsConsumedExactlyOnce(@TempDir Path dir) throws IOException {
        ClientRegistry registry = new ClientRegistry(dir.resolve("clients.json"));
        byte[] verifier = Kdf.derivePairingKey("one-time-key");
        registry.addPendingKey(java.util.Base64.getEncoder().encodeToString(verifier),
                System.currentTimeMillis() + 60_000L, "owner", System.currentTimeMillis());

        assertTrue(registry.consumePendingKey(verifier.clone(), System.currentTimeMillis()));
        assertFalse(registry.consumePendingKey(verifier.clone(), System.currentTimeMillis()));
    }

    @Test
    void anExpiredPendingKeyIsRefused(@TempDir Path dir) throws IOException {
        ClientRegistry registry = new ClientRegistry(dir.resolve("clients.json"));
        byte[] verifier = Kdf.derivePairingKey("expiring");
        registry.addPendingKey(java.util.Base64.getEncoder().encodeToString(verifier), 1_000L, "owner", 0L);

        assertFalse(registry.consumePendingKey(verifier, 2_000L));
    }

    @Test
    void clientsSurviveAReload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("clients.json");
        ClientRegistry registry = new ClientRegistry(file);
        ClientRegistry.Client client = registry.addClient("Laptop", Kdf.deriveClientKey("secret"), "uuid", "owner",
                "adminmod/1.0.0", 5_000L);
        registry.save();

        ClientRegistry reloaded = new ClientRegistry(file);
        reloaded.load();
        assertEquals(1, reloaded.clients().size());
        assertTrue(java.util.Arrays.equals(Kdf.deriveClientKey("secret"), reloaded.verifierOf(client.id())));
    }

    @Test
    void revokeRemovesOnlyTheNamedClient(@TempDir Path dir) throws IOException {
        ClientRegistry registry = new ClientRegistry(dir.resolve("clients.json"));
        ClientRegistry.Client a = registry.addClient("A", Kdf.deriveClientKey("a"), "u", "o", "b", 1L);
        registry.addClient("B", Kdf.deriveClientKey("b"), "u", "o", "b", 1L);

        assertTrue(registry.revoke(a.id()));
        assertEquals(1, registry.clients().size());
        assertNull(registry.client(a.id()));
    }

    @Test
    void aNewClientGetsAFreshIdAfterReload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("clients.json");
        ClientRegistry registry = new ClientRegistry(file);
        ClientRegistry.Client first = registry.addClient("A", Kdf.deriveClientKey("a"), "u", "o", "b", 1L);
        registry.save();

        ClientRegistry reloaded = new ClientRegistry(file);
        reloaded.load();
        ClientRegistry.Client second = reloaded.addClient("B", Kdf.deriveClientKey("b"), "u", "o", "b", 1L);
        // Ids must not collide across a restart, or a revocation could target the wrong client.
        assertFalse(first.id().equals(second.id()));
    }

    @Test
    void theFileHoldsNoPlaintextSecret(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("clients.json");
        ClientRegistry registry = new ClientRegistry(file);
        registry.addClient("Laptop", Kdf.deriveClientKey("super-secret"), "u", "o", "b", 1L);
        registry.save();

        assertFalse(Files.readString(file).contains("super-secret"));
    }
}
