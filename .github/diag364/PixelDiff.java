import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Prints where two PNGs differ: size, changed pixel count, max channel delta, bounding box, 32px blocks. */
public class PixelDiff {
    public static void main(String[] args) throws Exception {
        BufferedImage a = ImageIO.read(new File(args[0]));
        BufferedImage b = ImageIO.read(new File(args[1]));
        System.out.printf("%s: %dx%d vs %dx%d%n", args[1], a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight());
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return;
        }
        int block = 32;
        int bw = (a.getWidth() + block - 1) / block;
        int bh = (a.getHeight() + block - 1) / block;
        int[] blocks = new int[bw * bh];
        long changed = 0;
        int max = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        int[] hist = new int[9];
        StringBuilder samples = new StringBuilder();
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                if (p == q) {
                    continue;
                }
                int d = 0;
                for (int s = 0; s < 32; s += 8) {
                    d = Math.max(d, Math.abs(((p >>> s) & 0xff) - ((q >>> s) & 0xff)));
                }
                hist[Math.min(8, 32 - Integer.numberOfLeadingZeros(d))]++;
                if (changed < 12) {
                    samples.append(String.format(" (%d,%d) %08x->%08x", x, y, p, q));
                }
                changed++;
                max = Math.max(max, d);
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                blocks[(y / block) * bw + x / block]++;
            }
        }
        System.out.printf("changed=%d max_delta=%d bbox=%d,%d-%d,%d%n", changed, max, minX, minY, maxX, maxY);
        System.out.print("delta histogram (1,2-3,4-7,..,128-255):");
        for (int i = 1; i < 9; i++) System.out.print(" " + hist[i]);
        System.out.println();
        System.out.println("samples:" + samples);
        for (int y = 0; y < bh; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < bw; x++) {
                int n = blocks[y * bw + x];
                row.append(n == 0 ? '.' : n < 10 ? '1' : n < 100 ? '2' : '3');
            }
            System.out.println("  " + row);
        }
    }
}
