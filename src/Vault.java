import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * A folder of PNG images, one per website. Each image hides that site's login, encrypted with the master
 * password: credential -> bytes -> {@link VaultCrypto} -> {@link LsbStego} -> PNG.
 */
public final class Vault implements Deletable {

    private static final int MAX_NAME_LENGTH = 100;

    private final Path dir;
    private final VaultCrypto crypto;

    public Vault(Path dir, VaultCrypto crypto) {
        this.dir = dir;
        this.crypto = crypto;
    }

    public Path dir() {
        return dir;
    }

    /**
     * Turns a website into a safe file name: {@code "https://GitHub.com/"} becomes {@code "github.com.png"}.
     * Only letters, digits, '.', '-' and '_' survive, and the name never starts with '.', so input like
     * {@code "../../etc"} can't write outside the vault folder.
     */
    public static String fileNameFor(String website) {
        String name = website.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^[a-z][a-z0-9+.-]*://", "")
                .replaceFirst("/+$", "")
                .replaceAll("[^\\p{L}\\p{N}._-]", "_")
                .replaceFirst("^[._]+", "");
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("\"" + website + "\" has no letters or digits to name a file after.");
        }
        return name + ".png";
    }

    public Path pathFor(String website) {
        return dir.resolve(fileNameFor(website));
    }

    public boolean has(String website) {
        return Files.isRegularFile(pathFor(website));
    }

    /** Names of the saved images, without ".png", sorted. */
    public List<String> list() throws IOException {
        List<String> names = new ArrayList<>();
        for (Path file : imageFiles()) {
            String fileName = file.getFileName().toString();
            names.add(fileName.substring(0, fileName.length() - ".png".length()));
        }
        names.sort(null);
        return names;
    }

    /**
     * Encrypts the login and hides it in {@code cover}, or in a generated image when {@code cover} is null.
     * Replaces any image already saved for the same website.
     */
    public Path store(Credential credential, char[] masterPassword, BufferedImage cover) throws IOException {
        byte[] encrypted = crypto.encrypt(masterPassword, credential.toBytes());
        BufferedImage image = cover != null ? cover : CoverImage.generate(LsbStego.pixelsNeeded(encrypted.length));
        LsbStego.embed(image, encrypted);

        Files.createDirectories(dir);
        Path target = pathFor(credential.getWebsite());
        // Write to a temporary file first, then rename: a crash mid-write can't leave a half-written image behind.
        // createTempFile makes the file readable only by you; writing through a stream (not ImageIO.write(File),
        // which deletes and recreates the file) keeps it that way.
        Path temp = Files.createTempFile(dir, ".saving-", ".png");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                if (!ImageIO.write(image, "png", out)) {
                    throw new IOException("No PNG writer available");
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    public Credential retrieve(String website, char[] masterPassword) throws IOException, VaultException {
        return open(pathFor(website), masterPassword);
    }

    public Credential open(Path file, char[] masterPassword) throws IOException, VaultException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new VaultException.NotVaultImage(file.getFileName() + " is not a readable image");
        }
        return Credential.fromBytes(crypto.decrypt(masterPassword, LsbStego.extract(image)));
    }

    /**
     * Checks the master password by decrypting the first vault image that will open. Returns true when the
     * password is right or when there is nothing to check it against.
     */
    public boolean checkPassword(char[] masterPassword) throws IOException {
        for (Path file : imageFiles()) {
            try {
                open(file, masterPassword);
                return true;
            } catch (VaultException.WrongPassword e) {
                return false;
            } catch (VaultException e) {
                // Some other PNG in the folder: try the next one.
            }
        }
        return true;
    }

    @Override
    public boolean delete(String website) {
        try {
            return Files.deleteIfExists(pathFor(website));
        } catch (IOException e) {
            return false;
        }
    }

    private List<Path> imageFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "[!.]*.png")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }
        files.sort(null);
        return files;
    }
}
