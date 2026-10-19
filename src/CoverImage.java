import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Random;
import javax.imageio.ImageIO;

/** The picture the data is hidden in: either a photo the user picks or a generated abstract image. */
public final class CoverImage {

    static final int MIN_GENERATED_SIZE = 256;

    private CoverImage() {
    }

    /**
     * Loads any image Java can read (PNG, JPEG, BMP, GIF) into a plain RGB or ARGB buffer, so each pixel's bits
     * can be changed exactly. The result is always saved as PNG: JPEG compression would scramble the hidden bits.
     */
    public static BufferedImage load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("No image file at " + path);
        }
        BufferedImage source = ImageIO.read(path.toFile());
        if (source == null) {
            throw new IOException("Can't read " + path.getFileName() + " as an image. Use a PNG, JPEG, BMP or GIF.");
        }
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    /**
     * Makes a soft, colourful wave pattern with a little grain, at least {@value #MIN_GENERATED_SIZE} pixels
     * square. The grain matters: hidden bits look like natural noise in a noisy picture, but would stand out
     * in a perfectly flat one. A real photo still hides data better than anything generated.
     */
    public static BufferedImage generate(int pixelsNeeded) {
        int side = Math.max(MIN_GENERATED_SIZE, (int) Math.ceil(Math.sqrt(pixelsNeeded)));
        Random random = new SecureRandom();
        double[][] waves = new double[3][4]; // per channel: x frequency, y frequency, phase, brightness
        for (double[] wave : waves) {
            wave[0] = 0.5 + random.nextDouble() * 2.5;
            wave[1] = 0.5 + random.nextDouble() * 2.5;
            wave[2] = random.nextDouble() * 2 * Math.PI;
            wave[3] = 90 + random.nextDouble() * 80;
        }

        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                double fx = (double) x / side;
                double fy = (double) y / side;
                int rgb = 0;
                for (double[] wave : waves) {
                    double value = wave[3] + 60 * Math.sin(2 * Math.PI * (fx * wave[0] + fy * wave[1]) + wave[2])
                            + random.nextGaussian() * 4;
                    rgb = (rgb << 8) | (int) Math.max(0, Math.min(255, Math.round(value)));
                }
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }
}
