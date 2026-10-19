import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/** One saved login. */
public class Credential {
    /** Longest field we accept, well under the 65,535-byte limit of DataOutputStream.writeUTF. */
    public static final int MAX_FIELD_LENGTH = 1000;

    private final String website;
    private final String username;
    private final String password;

    public Credential(String website, String username, String password) {
        this.website = requireField("website", website);
        this.username = requireField("username", username);
        this.password = requireField("password", password);
    }

    public String getWebsite() {
        return website;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String toFormattedString() {
        return "Website:  " + website + "\n" +
               "Username: " + username + "\n" +
               "Password: " + password;
    }

    /**
     * Each field is written with its length in front (writeUTF), so a "|" or any other character inside a
     * field can't be mistaken for a separator. The text is stored as UTF-8, so every language and emoji works.
     */
    public byte[] toBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(website);
            out.writeUTF(username);
            out.writeUTF(password);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // can't happen when writing to memory
        }
        return bytes.toByteArray();
    }

    public static Credential fromBytes(byte[] data) throws VaultException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            Credential credential = new Credential(in.readUTF(), in.readUTF(), in.readUTF());
            if (in.available() != 0) {
                throw new VaultException.NotVaultImage("unexpected extra data");
            }
            return credential;
        } catch (IOException | IllegalArgumentException e) {
            throw new VaultException.NotVaultImage("the saved login is damaged");
        }
    }

    private static String requireField(String name, String value) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("The " + name + " can't be empty.");
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("The " + name + " is longer than " + MAX_FIELD_LENGTH + " characters.");
        }
        return value;
    }
}
