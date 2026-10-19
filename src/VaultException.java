/** A problem the user can cause and recover from: a wrong password or a file that isn't a vault image. */
public class VaultException extends Exception {

    public VaultException(String message) {
        super(message);
    }

    /**
     * AES-GCM refused to decrypt. That happens with a wrong master password and also when even one bit
     * of the image's hidden data was changed: GCM can't tell the two apart, and that's by design.
     */
    public static final class WrongPassword extends VaultException {
        public WrongPassword() {
            super("Wrong master password, or the image has been modified.");
        }
    }

    /** The image has no StegoVault data in it, or the data is in a format this version doesn't know. */
    public static final class NotVaultImage extends VaultException {
        public NotVaultImage(String detail) {
            super("Not a StegoVault image: " + detail);
        }
    }
}
