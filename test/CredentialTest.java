import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class CredentialTest {

    @Test
    public void roundTripsThroughBytes() throws Exception {
        Credential original = new Credential("github.com", "ansh", "S3cret!");

        Credential copy = Credential.fromBytes(original.toBytes());

        assertEquals("github.com", copy.getWebsite());
        assertEquals("ansh", copy.getUsername());
        assertEquals("S3cret!", copy.getPassword());
    }

    @Test
    public void keepsNonAsciiCharacters() throws Exception {
        Credential copy = Credential.fromBytes(new Credential("例え.jp", "zoë", "p€ss🔑").toBytes());

        assertEquals("例え.jp", copy.getWebsite());
        assertEquals("zoë", copy.getUsername());
        assertEquals("p€ss🔑", copy.getPassword());
    }

    @Test
    public void separatorCharactersInsideFieldsAreKept() throws Exception {
        Credential copy = Credential.fromBytes(new Credential("site", "an|sh", "pw|with|pipes\0and null").toBytes());

        assertEquals("an|sh", copy.getUsername());
        assertEquals("pw|with|pipes\0and null", copy.getPassword());
    }

    @Test
    public void rejectsEmptyFields() {
        assertThrows(IllegalArgumentException.class, () -> new Credential("site", "", "pw"));
    }

    @Test
    public void rejectsOverlongFields() {
        String tooLong = "x".repeat(Credential.MAX_FIELD_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> new Credential("site", "user", tooLong));
    }

    @Test
    public void damagedBytesAreReported() {
        assertThrows(VaultException.NotVaultImage.class, () -> Credential.fromBytes(new byte[] {0, 50, 'a'}));
    }
}
