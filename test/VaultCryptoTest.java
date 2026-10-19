import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class VaultCryptoTest {

    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();
    private static final byte[] SECRET = "ansh|S3cret!".getBytes(StandardCharsets.UTF_8);

    // Few iterations so the tests run fast; the count is stored in each blob anyway.
    private final VaultCrypto crypto = new VaultCrypto(VaultCrypto.MIN_ITERATIONS);

    @Test
    public void decryptsWhatItEncrypted() throws Exception {
        byte[] blob = crypto.encrypt(PASSWORD, SECRET);

        assertArrayEquals(SECRET, crypto.decrypt(PASSWORD, blob));
    }

    @Test
    public void ciphertextDoesNotContainThePlaintext() {
        byte[] blob = crypto.encrypt(PASSWORD, SECRET);

        String asText = new String(blob, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("S3cret!"));
    }

    @Test
    public void sameInputEncryptsDifferentlyEachTime() {
        // Fresh random salt and nonce every time, so equal passwords can't be spotted by comparing images.
        assertFalse(Arrays.equals(crypto.encrypt(PASSWORD, SECRET), crypto.encrypt(PASSWORD, SECRET)));
    }

    @Test
    public void blobIsHeaderPlusPlaintextPlusTag() {
        assertEquals(VaultCrypto.HEADER_BYTES + SECRET.length + 16, crypto.encrypt(PASSWORD, SECRET).length);
    }

    @Test
    public void wrongPasswordIsRejected() {
        byte[] blob = crypto.encrypt(PASSWORD, SECRET);

        assertThrows(VaultException.WrongPassword.class, () -> crypto.decrypt("wrong password".toCharArray(), blob));
    }

    @Test
    public void flippingAnyCiphertextBitIsDetected() {
        byte[] blob = crypto.encrypt(PASSWORD, SECRET);
        blob[VaultCrypto.HEADER_BYTES] ^= 1;

        assertThrows(VaultException.WrongPassword.class, () -> crypto.decrypt(PASSWORD, blob));
    }

    @Test
    public void tamperingWithTheNonceIsDetected() {
        byte[] blob = crypto.encrypt(PASSWORD, SECRET);
        blob[VaultCrypto.HEADER_BYTES - 1] ^= 1; // last nonce byte

        assertThrows(VaultException.WrongPassword.class, () -> crypto.decrypt(PASSWORD, blob));
    }

    @Test
    public void usesTheIterationCountStoredInTheBlob() throws Exception {
        byte[] blob = new VaultCrypto(2_000).encrypt(PASSWORD, SECRET);

        assertArrayEquals(SECRET, crypto.decrypt(PASSWORD, blob));
    }

    @Test
    public void randomBytesAreNotAVaultBlob() {
        byte[] junk = new byte[100];

        assertThrows(VaultException.NotVaultImage.class, () -> crypto.decrypt(PASSWORD, junk));
    }

    @Test
    public void rejectsUnsafeIterationCounts() {
        assertThrows(IllegalArgumentException.class, () -> new VaultCrypto(10));
    }
}
