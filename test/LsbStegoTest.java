import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

public class LsbStegoTest {

    private final Random random = new Random(42);

    @Test
    public void extractsWhatWasEmbedded() throws Exception {
        BufferedImage image = noise(20, 20, BufferedImage.TYPE_INT_RGB);
        byte[] data = randomBytes(100);

        LsbStego.embed(image, data);

        assertArrayEquals(data, LsbStego.extract(image));
    }

    @Test
    public void changesEachColourValueByAtMostOne() {
        BufferedImage image = noise(20, 20, BufferedImage.TYPE_INT_ARGB);
        BufferedImage original = copy(image);

        LsbStego.embed(image, randomBytes(LsbStego.capacityBytes(20, 20)));

        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                int before = original.getRGB(x, y);
                int after = image.getRGB(x, y);
                assertEquals(before >>> 24, after >>> 24, "alpha must not change");
                for (int shift : new int[] {16, 8, 0}) {
                    int difference = Math.abs(((before >> shift) & 0xFF) - ((after >> shift) & 0xFF));
                    assertTrue(difference <= 1, "a colour value changed by more than 1");
                }
            }
        }
    }

    @Test
    public void survivesSavingAsPng() throws Exception {
        BufferedImage image = noise(16, 16, BufferedImage.TYPE_INT_RGB);
        byte[] data = randomBytes(60);
        LsbStego.embed(image, data);

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        BufferedImage reloaded = ImageIO.read(new ByteArrayInputStream(png.toByteArray()));

        assertArrayEquals(data, LsbStego.extract(reloaded));
    }

    @Test
    public void worksOnTransparentPixels() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB); // all fully transparent
        byte[] data = randomBytes(30);
        LsbStego.embed(image, data);

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        assertArrayEquals(data, LsbStego.extract(ImageIO.read(new ByteArrayInputStream(png.toByteArray()))));
    }

    @Test
    public void fillsAnImageExactly() throws Exception {
        BufferedImage image = noise(8, 8, BufferedImage.TYPE_INT_RGB); // 192 bits: 32 for the length + 20 bytes
        assertEquals(20, LsbStego.capacityBytes(8, 8));
        byte[] data = randomBytes(20);

        LsbStego.embed(image, data);

        assertArrayEquals(data, LsbStego.extract(image));
    }

    @Test
    public void refusesDataThatDoesNotFit() {
        BufferedImage image = noise(8, 8, BufferedImage.TYPE_INT_RGB);

        assertThrows(IllegalArgumentException.class, () -> LsbStego.embed(image, new byte[21]));
    }

    @Test
    public void pixelsNeededMatchesCapacity() {
        for (int bytes = 1; bytes < 500; bytes++) {
            int side = (int) Math.ceil(Math.sqrt(LsbStego.pixelsNeeded(bytes)));
            assertTrue(LsbStego.capacityBytes(side, side) >= bytes);
        }
    }

    @Test
    public void plainImageHasNoHiddenData() {
        BufferedImage white = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                white.setRGB(x, y, 0xFFFFFF); // every LSB is 1, so the "length" would be -1
            }
        }

        assertThrows(VaultException.NotVaultImage.class, () -> LsbStego.extract(white));
    }

    private BufferedImage noise(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
        copy.setData(image.getData());
        return copy;
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }
}
