import java.awt.image.BufferedImage;

/**
 * Hides bytes in the least significant bit (LSB) of each pixel's red, green and blue values.
 *
 * Changing the lowest bit moves a colour value by at most 1 out of 255, which the eye can't see. Each pixel
 * therefore carries 3 hidden bits. Pixels are used row by row from the top-left, channels in R, G, B order.
 *
 * Hidden layout: a 32-bit big-endian length (in bytes), then the bytes themselves, most significant bit first.
 * Alpha (transparency) is never touched.
 *
 * Steganography only hides that a message exists. Anyone who knows the layout can read the bits back out, which
 * is why {@link Vault} only ever hides data that {@link VaultCrypto} has already encrypted.
 */
public final class LsbStego {

    private static final int LENGTH_BITS = 32;
    private static final int[] CHANNEL_SHIFTS = {16, 8, 0}; // red, green, blue inside a 0xAARRGGBB int

    private LsbStego() {
    }

    /** How many bytes fit in an image of this size, after the length header. */
    public static int capacityBytes(int width, int height) {
        long bits = (long) width * height * CHANNEL_SHIFTS.length - LENGTH_BITS;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, bits / 8));
    }

    /** How many pixels it takes to hide {@code dataBytes} bytes plus the length header. */
    public static int pixelsNeeded(int dataBytes) {
        long bits = LENGTH_BITS + (long) dataBytes * 8;
        return (int) ((bits + CHANNEL_SHIFTS.length - 1) / CHANNEL_SHIFTS.length);
    }

    /** Writes {@code data} into the image's pixels in place. The image must be TYPE_INT_RGB or TYPE_INT_ARGB. */
    public static void embed(BufferedImage image, byte[] data) {
        int capacity = capacityBytes(image.getWidth(), image.getHeight());
        if (data.length > capacity) {
            throw new IllegalArgumentException("Image is too small: it can hide " + capacity
                    + " bytes but " + data.length + " are needed (at least " + pixelsNeeded(data.length) + " pixels).");
        }
        int width = image.getWidth();
        long totalBits = LENGTH_BITS + (long) data.length * 8;
        for (int pixel = 0; (long) pixel * CHANNEL_SHIFTS.length < totalBits; pixel++) {
            int x = pixel % width;
            int y = pixel / width;
            int argb = image.getRGB(x, y);
            for (int channel = 0; channel < CHANNEL_SHIFTS.length; channel++) {
                int bitIndex = pixel * CHANNEL_SHIFTS.length + channel;
                if (bitIndex >= totalBits) {
                    break;
                }
                int shift = CHANNEL_SHIFTS[channel];
                argb = (argb & ~(1 << shift)) | (bitAt(data, bitIndex) << shift);
            }
            image.setRGB(x, y, argb);
        }
    }

    /** Reads back what {@link #embed} wrote. */
    public static byte[] extract(BufferedImage image) throws VaultException {
        int capacity = capacityBytes(image.getWidth(), image.getHeight());
        int length = readBits(image, 0, LENGTH_BITS);
        if (length <= 0 || length > capacity) {
            throw new VaultException.NotVaultImage("no hidden data found");
        }
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) readBits(image, LENGTH_BITS + i * 8, 8);
        }
        return data;
    }

    /** Bit number {@code index} of the stream "32-bit length, then data". */
    private static int bitAt(byte[] data, int index) {
        if (index < LENGTH_BITS) {
            return (data.length >>> (LENGTH_BITS - 1 - index)) & 1;
        }
        int dataBit = index - LENGTH_BITS;
        return (data[dataBit / 8] >> (7 - dataBit % 8)) & 1;
    }

    private static int readBits(BufferedImage image, int start, int count) {
        int width = image.getWidth();
        int value = 0;
        for (int index = start; index < start + count; index++) {
            int pixel = index / CHANNEL_SHIFTS.length;
            int argb = image.getRGB(pixel % width, pixel / width);
            value = (value << 1) | ((argb >> CHANNEL_SHIFTS[index % CHANNEL_SHIFTS.length]) & 1);
        }
        return value;
    }
}
