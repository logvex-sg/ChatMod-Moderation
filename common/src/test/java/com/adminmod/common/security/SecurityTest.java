package com.adminmod.common.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityTest {

    @Test
    void samePasswordProducesSameVerifier() {
        char[] password = "correct horse battery staple".toCharArray();
        assertArrayEquals(Kdf.derivePasswordKey(password), Kdf.derivePasswordKey(password));
    }

    @Test
    void differentPasswordsProduceDifferentVerifiers() {
        assertFalse(Kdf.constantTimeEquals(
                Kdf.derivePasswordKey("one".toCharArray()),
                Kdf.derivePasswordKey("two".toCharArray())));
    }

    @Test
    void sessionKeyDependsOnBothNonces() {
        byte[] passwordKey = Kdf.derivePasswordKey("secret".toCharArray());
        byte[] a = Kdf.deriveSessionKey(passwordKey.clone(), new byte[16], new byte[16]);
        byte[] b = Kdf.deriveSessionKey(passwordKey.clone(), new byte[16], new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    void sessionKeyIsSymmetricWhenNoncesAreSwappedInOrder() {
        byte[] passwordKey = Kdf.derivePasswordKey("secret".toCharArray());
        byte[] clientNonce = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] serverNonce = {16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        byte[] client = Kdf.deriveSessionKey(passwordKey.clone(), clientNonce, serverNonce);
        byte[] server = Kdf.deriveSessionKey(passwordKey.clone(), clientNonce, serverNonce);
        assertArrayEquals(client, server);
    }

    @Test
    void cipherRoundTrips() throws Exception {
        SessionCipher cipher = new SessionCipher(new byte[32]);
        byte[] plaintext = "console order".getBytes();
        byte[] sealed = cipher.encrypt(plaintext, 1L, 1000L);
        assertArrayEquals(plaintext, cipher.decrypt(sealed, 1L, 1000L));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        SessionCipher cipher = new SessionCipher(new byte[32]);
        byte[] sealed = cipher.encrypt("hello".getBytes(), 1L, 1000L);
        sealed[0] ^= 0x01;
        assertThrows(SessionException.class, () -> cipher.decrypt(sealed, 1L, 1000L));
    }

    @Test
    void tamperedTimestampIsRejected() {
        SessionCipher cipher = new SessionCipher(new byte[32]);
        byte[] sealed = cipher.encrypt("hello".getBytes(), 1L, 1000L);
        // The timestamp is authenticated data: changing it must break the tag.
        assertThrows(SessionException.class, () -> cipher.decrypt(sealed, 1L, 1001L));
    }

    @Test
    void wrongKeyIsRejected() {
        SessionCipher sender = new SessionCipher(new byte[32]);
        SessionCipher receiver = new SessionCipher(filled((byte) 9, 32));
        byte[] sealed = sender.encrypt("hello".getBytes(), 1L, 1000L);
        assertThrows(SessionException.class, () -> receiver.decrypt(sealed, 1L, 1000L));
    }

    @Test
    void distinctCiphersHaveDistinctFingerprints() {
        assertNotEquals(new SessionCipher(new byte[32]).fingerprint(), new SessionCipher(filled((byte) 1, 32)).fingerprint());
    }

    @Test
    void vaultVerifiesGeneratedPassword(@TempDir Path dir) throws Exception {
        PasswordVault.Created created = PasswordVault.create();
        Path file = dir.resolve("vault").resolve("credential.dat");
        created.vault().save(file);

        PasswordVault reloaded = PasswordVault.load(file);
        assertTrue(reloaded.verify(created.password()));
        assertFalse(reloaded.verify("not the password".toCharArray()));
        assertEquals(28, created.password().length);
    }

    @Test
    void vaultFileContainsNoPlaintextPassword(@TempDir Path dir) throws Exception {
        PasswordVault.Created created = PasswordVault.create();
        Path file = dir.resolve("credential.dat");
        created.vault().save(file);
        String content = java.nio.file.Files.readString(file);
        assertFalse(content.contains(new String(created.password())));
    }

    @Test
    void randomFolderNamesAreUnique() {
        assertNotEquals(PasswordVault.randomFolderName(), PasswordVault.randomFolderName());
        assertEquals(48, PasswordVault.randomFolderName().length());
    }

    private static byte[] filled(byte value, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
