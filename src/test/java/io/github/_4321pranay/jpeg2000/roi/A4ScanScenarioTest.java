package io.github._4321pranay.jpeg2000.roi;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.Random;

import static io.github._4321pranay.jpeg2000.roi.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Production scenario: A4 page scanned at 300 DPI (2480 x 3508 px), a QR printed at 1.2 inch, scan
 * blur and sensor noise, compressed with ROI to a 300 KB cap.
 */
class A4ScanScenarioTest {

    static final int W = 2480, H = 3508;                 // A4 @ 300 DPI
    static final int CAP_BYTES = 300_000;
    /** The rate target covers the codestream only; the JP2 wrapper adds ~100 bytes on top. */
    static final int WRAPPER_MARGIN = 1_024;
    static final int QR_PX = 360;                         // 1.2 inch @ 300 DPI
    static final Rectangle QR = new Rectangle(1900, 3000, QR_PX, QR_PX);

    @Test
    void grayScanAt300KB() throws Exception {
        run("gray", BufferedImage.TYPE_BYTE_GRAY);
    }

    @Test
    void colourScanAt300KB() throws Exception {
        run("colour", BufferedImage.TYPE_3BYTE_BGR);
    }

    private void run(String label, int type) throws Exception {
        assumeTrue(toolAvailable("opj_decompress"), "OpenJPEG not installed");
        BufferedImage scan = scannedA4(type);
        double bpp = (CAP_BYTES - WRAPPER_MARGIN) * 8.0 / (W * (double) H);

        RoiJ2KImageWriter w1 = roiWriter();
        byte[] roi = encode(w1, scan, lossyParam(w1, bpp, QR));
        RoiJ2KImageWriter w2 = roiWriter();
        byte[] plain = encode(w2, scan, lossyParam(w2, bpp));

        BufferedImage roiDec = decodeWithOpenJpeg(roi, ".jp2");
        BufferedImage plainDec = decodeWithOpenJpeg(plain, ".jp2");
        Rectangle text = new Rectangle(0, 0, W, 2800);

        System.out.printf("A4 %s: raw %,d B, cap %,d B, bpp %.3f%n", label,
                (long) W * H * (type == BufferedImage.TYPE_BYTE_GRAY ? 1 : 3), CAP_BYTES, bpp);
        System.out.printf("  ROI   : %,7d B  QR %6.2f dB scan=%-5s  page %5.2f dB%n", roi.length,
                psnr(scan, roiDec, QR), decodeQr(roiDec.getSubimage(QR.x - 40, QR.y - 40, QR.width + 80, QR.height + 80)) != null,
                psnr(scan, roiDec, text));
        System.out.printf("  plain : %,7d B  QR %6.2f dB scan=%-5s  page %5.2f dB%n", plain.length,
                psnr(scan, plainDec, QR), decodeQr(plainDec.getSubimage(QR.x - 40, QR.y - 40, QR.width + 80, QR.height + 80)) != null,
                psnr(scan, plainDec, text));

        assertTrue(roi.length <= CAP_BYTES, "over the cap: " + roi.length);
        assertEquals(99.99, psnr(scan, roiDec, QR), "QR (incl. scan noise) should be complete within 300 KB");
        assertNotNull(decodeQr(roiDec));
    }

    /** How many bytes does the QR alone need? Smallest file size at which the QR is complete. */
    @Test
    void qrCostOnGrayScan() throws Exception {
        assumeTrue(toolAvailable("opj_decompress"), "OpenJPEG not installed");
        BufferedImage scan = scannedA4(BufferedImage.TYPE_BYTE_GRAY);
        for (int kb : new int[] {20, 40, 60, 80, 100, 150}) {
            RoiJ2KImageWriter w = roiWriter();
            byte[] data = encode(w, scan, lossyParam(w, kb * 1000 * 8.0 / (W * (double) H), QR));
            BufferedImage dec = decodeWithOpenJpeg(data, ".jp2");
            System.out.printf("  %3d KB: QR %6.2f dB scan=%-5s  page %5.2f dB%n", kb, psnr(scan, dec, QR),
                    decodeQr(dec.getSubimage(QR.x - 40, QR.y - 40, QR.width + 80, QR.height + 80)) != null,
                    psnr(scan, dec, new Rectangle(0, 0, W, 2800)));
        }
    }

    /** Text page with a printed QR, then scanner blur + noise. */
    static BufferedImage scannedA4(int type) {
        BufferedImage img = new BufferedImage(W, H, type);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(250, 248, 242));                       // off-white paper
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(25, 25, 35));
        g.setFont(new Font(Font.SERIF, Font.BOLD, 70));
        g.drawString("INVOICE #2026-1103", 250, 330);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 42));               // ~10 pt at 300 DPI
        Random rnd = new Random(7);
        String[] words = {"payment", "amount", "due", "total", "invoice", "date", "tax", "item", "qty"};
        for (int y = 480; y < 2750; y += 62) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 13; i++) sb.append(words[rnd.nextInt(words.length)]).append(' ');
            g.drawString(sb.toString(), 250, y);
        }
        g.setColor(new Color(30, 60, 140));                          // a coloured stamp/logo
        g.fillOval(300, 2900, 400, 400);
        g.drawImage(qr(QR_TEXT, QR_PX), QR.x, QR.y, null);
        g.dispose();

        // scanner optics blur
        float[] k = {1 / 16f, 2 / 16f, 1 / 16f, 2 / 16f, 4 / 16f, 2 / 16f, 1 / 16f, 2 / 16f, 1 / 16f};
        BufferedImage blurred = new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
        // sensor noise
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = blurred.getRGB(x, y);
                int n = (int) Math.round(rnd.nextGaussian() * 4);
                int r = clamp(((rgb >> 16) & 0xFF) + n), gg = clamp(((rgb >> 8) & 0xFF) + n), b = clamp((rgb & 0xFF) + n);
                blurred.setRGB(x, y, (r << 16) | (gg << 8) | b);
            }
        }
        return blurred;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
