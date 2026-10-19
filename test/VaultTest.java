import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class VaultTest {

    private static final char[] MASTER = "master password".toCharArray();

    @TempDir
    Path dir;

    private Vault vault() {
        return new Vault(dir.resolve("vault"), new VaultCrypto(VaultCrypto.MIN_ITERATIONS));
    }

    @Test
    public void storesAndRetrievesInAGeneratedImage() throws Exception {
        Vault vault = vault();
        Path saved = vault.store(new Credential("GitHub.com", "ansh", "p€ss🔑"), MASTER, null);

        assertEquals("github.com.png", saved.getFileName().toString());
        Credential back = vault.retrieve("github.com", MASTER);
        assertEquals("GitHub.com", back.getWebsite());
        assertEquals("ansh", back.getUsername());
        assertEquals("p€ss🔑", back.getPassword());
    }

    @Test
    public void generatedImageIsNotATinyDarkSquare() throws Exception {
        Path saved = vault().store(new Credential("site", "user", "pw"), MASTER, null);
        BufferedImage image = ImageIO.read(saved.toFile());

        assertTrue(image.getWidth() >= CoverImage.MIN_GENERATED_SIZE);
        long brightness = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            brightness += image.getRGB(x, image.getHeight() / 2) & 0xFF;
        }
        assertTrue(brightness / image.getWidth() > 20, "generated image is almost black");
    }

    @Test
    public void storesInAJpegPhotoAsPng() throws Exception {
        BufferedImage photo = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                photo.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | 120);
            }
        }
        Path jpeg = dir.resolve("holiday photo.jpg");
        ImageIO.write(photo, "jpg", jpeg.toFile());
        Vault vault = vault();

        vault.store(new Credential("bank", "me", "hunter2"), MASTER, CoverImage.load(jpeg));

        BufferedImage saved = ImageIO.read(vault.pathFor("bank").toFile());
        assertEquals(64, saved.getWidth());
        assertEquals(48, saved.getHeight());
        assertEquals("hunter2", vault.retrieve("bank", MASTER).getPassword());
    }

    @Test
    public void tooSmallPhotoIsRefused() throws Exception {
        Path tiny = dir.resolve("tiny.png");
        ImageIO.write(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "png", tiny.toFile());

        assertThrows(IllegalArgumentException.class,
                () -> vault().store(new Credential("site", "user", "pw"), MASTER, CoverImage.load(tiny)));
    }

    @Test
    public void wrongMasterPasswordCannotRead() throws Exception {
        Vault vault = vault();
        vault.store(new Credential("site", "user", "pw"), MASTER, null);

        assertThrows(VaultException.WrongPassword.class, () -> vault.retrieve("site", "guess".toCharArray()));
        assertFalse(vault.checkPassword("guess".toCharArray()));
        assertTrue(vault.checkPassword(MASTER));
    }

    @Test
    public void checkPasswordSkipsImagesThatAreNotVaultImages() throws Exception {
        Vault vault = vault();
        vault.store(new Credential("site", "user", "pw"), MASTER, null);
        ImageIO.write(new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB), "png", vault.dir().resolve("aaa-cat.png").toFile());

        assertTrue(vault.checkPassword(MASTER));
        assertFalse(vault.checkPassword("guess".toCharArray()));
    }

    @Test
    public void savingAgainReplacesTheOldLogin() throws Exception {
        Vault vault = vault();
        vault.store(new Credential("site", "user", "old"), MASTER, null);
        vault.store(new Credential("site", "user", "new"), MASTER, null);

        assertEquals("new", vault.retrieve("site", MASTER).getPassword());
        assertEquals(List.of("site"), vault.list());
    }

    @Test
    public void listsAndDeletes() throws Exception {
        Vault vault = vault();
        assertEquals(List.of(), vault.list());
        vault.store(new Credential("b.com", "u", "p"), MASTER, null);
        vault.store(new Credential("a.com", "u", "p"), MASTER, null);

        assertEquals(List.of("a.com", "b.com"), vault.list());
        assertTrue(vault.delete("a.com"));
        assertFalse(vault.delete("a.com"));
        assertEquals(List.of("b.com"), vault.list());
    }

    @Test
    public void leavesNoTemporaryFilesBehind() throws Exception {
        Vault vault = vault();
        vault.store(new Credential("site", "user", "pw"), MASTER, null);

        try (var files = Files.list(vault.dir())) {
            assertEquals(List.of("site.png"), files.map(f -> f.getFileName().toString()).toList());
        }
    }

    @Test
    public void savedImagesAreReadableOnlyByTheOwner() throws Exception {
        Path saved = vault().store(new Credential("site", "user", "pw"), MASTER, null);

        if (saved.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved)));
        }
    }

    @Test
    public void fileNamesAreSafe() {
        assertEquals("github.com.png", Vault.fileNameFor("https://GitHub.com/"));
        assertEquals("x.com_login.png", Vault.fileNameFor("http://x.com/login"));
        assertEquals("my_bank.png", Vault.fileNameFor("  My Bank  "));
        assertEquals("etc_passwd.png", Vault.fileNameFor("../../etc/passwd"));
        assertEquals("hidden.png", Vault.fileNameFor(".hidden"));
        assertEquals("例え.jp.png", Vault.fileNameFor("例え.jp"));
        assertThrows(IllegalArgumentException.class, () -> Vault.fileNameFor("../"));
    }

    @Test
    public void cleansDraggedInPaths() {
        assertEquals("/Users/me/My Photo.jpg", MainApp.cleanPath("/Users/me/My\\ Photo.jpg "));
        assertEquals("/Users/me/My Photo.jpg", MainApp.cleanPath("'/Users/me/My Photo.jpg'"));
        assertEquals(System.getProperty("user.home") + "/a.png", MainApp.cleanPath("~/a.png"));
    }
}
