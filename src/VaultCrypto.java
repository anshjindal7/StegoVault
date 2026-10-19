import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-based authenticated encryption.
 *
 * <ol>
 *   <li><b>PBKDF2-HMAC-SHA256</b> turns the master password into a 256-bit key. It runs HMAC hundreds of
 *       thousands of times on purpose, so each password guess costs an attacker the same slow work.</li>
 *   <li>A random <b>salt</b> goes into that derivation, so the same password gives a different key for
 *       every image and precomputed tables of common passwords are useless.</li>
 *   <li><b>AES-256-GCM</b> encrypts the data and adds a 16-byte authentication tag. Decryption checks the tag
 *       first, so a wrong password or any tampering is detected instead of producing garbage.</li>
 * </ol>
 *
 * Output ("blob") layout, all integers big-endian:
 * <pre>
 *  offset  size  field
 *  0       4     magic "SVLT"
 *  4       1     format version (1)
 *  5       4     PBKDF2 iteration count
 *  9       16    salt
 *  25      12    AES-GCM nonce (IV)
 *  37      n+16  ciphertext followed by the authentication tag
 * </pre>
 * The 37 header bytes are passed to GCM as "additional authenticated data": they stay readable (decryption
 * needs them) but changing any of them makes the tag check fail.
 */
public final class VaultCrypto {

    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    static final int MIN_ITERATIONS = 1_000;
    static final int MAX_ITERATIONS = 10_000_000;

    private static final byte[] MAGIC = {'S', 'V', 'L', 'T'};
    private static final byte VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    static final int HEADER_BYTES = MAGIC.length + 1 + Integer.BYTES + SALT_BYTES + NONCE_BYTES;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public VaultCrypto() {
        this(DEFAULT_ITERATIONS);
    }

    /** Lower iteration counts are only for tests; the count used is stored in each blob. */
    public VaultCrypto(int iterations) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations must be between " + MIN_ITERATIONS + " and " + MAX_ITERATIONS);
        }
        this.iterations = iterations;
    }

    public byte[] encrypt(char[] password, byte[] plaintext) {
        byte[] salt = randomBytes(SALT_BYTES);
        // A GCM nonce must never repeat under the same key. The key is new for every blob (fresh salt) and the
        // nonce is random too, so a repeat is practically impossible.
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] header = ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(nonce)
                .array();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(header.length + ciphertext.length).put(header).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java runtime is missing AES-GCM or PBKDF2", e);
        }
    }

    public byte[] decrypt(char[] password, byte[] blob) throws VaultException {
        if (blob.length < HEADER_BYTES + TAG_BITS / 8 || !Arrays.equals(blob, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new VaultException.NotVaultImage("no StegoVault header found");
        }
        ByteBuffer in = ByteBuffer.wrap(blob, MAGIC.length, HEADER_BYTES - MAGIC.length);
        byte version = in.get();
        if (version != VERSION) {
            throw new VaultException.NotVaultImage("unsupported format version " + version);
        }
        int storedIterations = in.getInt();
        if (storedIterations < MIN_ITERATIONS || storedIterations > MAX_ITERATIONS) {
            throw new VaultException.NotVaultImage("invalid iteration count");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        in.get(salt).get(nonce);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, storedIterations), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(blob, 0, HEADER_BYTES);
            return cipher.doFinal(blob, HEADER_BYTES, blob.length - HEADER_BYTES);
        } catch (AEADBadTagException e) {
            throw new VaultException.WrongPassword();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java runtime is missing AES-GCM or PBKDF2", e);
        }
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(key, "AES"); // copies the bytes, so ours can be wiped
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
