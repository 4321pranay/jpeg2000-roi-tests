package io.github._4321pranay.jpeg2000.roi;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Test images, encoding/decoding helpers and quality metrics. */
final class TestSupport {

    static final String QR_TEXT = "https://example.com/verify?doc=2026-1102&sig=9f8e7d6c5b4a39281706";

    /** Where the QR is drawn in {@link #documentPage(int)} (including a 4-module quiet zone). */
    static final Rectangle QR_BOX = new Rectangle(500, 600, 260, 260);

    private TestSupport() {}

    /** An 800x1000 "scanned document": text lines, a noisy grey photo block, and a QR code. */
    static BufferedImage documentPage(int imageType) {
        int w = 800, h = 1000;
        BufferedImage img = new BufferedImage(w, h, imageType);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(20, 20, 60));
        g.setFont(new Font(Font.SERIF, Font.BOLD, 28));
        g.drawString("INVOICE #2026-1102", 60, 80);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 14));
        Random rnd = new Random(42);
        String[] words = {"lorem", "ipsum", "dolor", "sit", "amet"};
        for (int y = 130; y < h - 60; y += 22) {
            if (y > 560 && y < 900) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 14; i++) sb.append(words[rnd.nextInt(words.length)]).append(' ');
            g.drawString(sb.toString(), 60, y);
        }
        for (int y = 600; y < 860; y++) {
            for (int x = 60; x < 440; x++) {
                int v = 90 + (int) (60 * Math.sin(x / 17.0) * Math.cos(y / 23.0)) + rnd.nextInt(30);
                img.setRGB(x, y, new Color(v, Math.min(255, v + 20), Math.max(0, v - 20)).getRGB());
            }
        }
        g.drawImage(qr(QR_TEXT, QR_BOX.width), QR_BOX.x, QR_BOX.y, null);
        g.dispose();
        return img;
    }

    static BufferedImage qr(String text, int size) {
        try {
            return MatrixToImageWriter.toBufferedImage(
                    new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size));
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Encodes with the given writer/param into memory. */
    static byte[] encode(ImageWriter writer, BufferedImage img, ImageWriteParam param) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    static RoiJ2KImageWriter roiWriter() {
        return (RoiJ2KImageWriter) ImageIO.getImageWritersByFormatName(RoiJ2KImageWriterSpi.FORMAT_NAME).next();
    }

    /** Lossy ROI param: 5/3 filter so the ROI ends up bit-exact. */
    static RoiJ2KImageWriteParam lossyParam(RoiJ2KImageWriter w, double bpp, Rectangle... rois) {
        RoiJ2KImageWriteParam p = (RoiJ2KImageWriteParam) w.getDefaultWriteParam();
        p.setLossless(false);
        p.setEncodingRate(bpp);
        p.setFilter(RoiJ2KImageWriteParam.FILTER_53);
        for (Rectangle r : rois) p.addROI(r);
        return p;
    }

    // ---------- OpenJPEG / Poppler (external tools; tests are skipped when missing) ----------

    static boolean toolAvailable(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "-h").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Decodes JPEG 2000 bytes with OpenJPEG's opj_decompress. */
    static BufferedImage decodeWithOpenJpeg(byte[] data, String suffix) throws Exception {
        Path dir = Files.createTempDirectory("opj");
        try {
            Path in = dir.resolve("in" + suffix);
            Path out = dir.resolve("out.bmp");
            Files.write(in, data);
            run("opj_decompress", "-i", in.toString(), "-o", out.toString());
            return ImageIO.read(out.toFile());
        } finally {
            deleteRecursively(dir.toFile());
        }
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " failed:\n" + log);
        }
    }

    static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    // ---------- metrics ----------

    /** PSNR over a region (all RGB channels); 99.99 means identical. */
    static double psnr(BufferedImage a, BufferedImage b, Rectangle r) {
        double mse = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int s = 0; s <= 16; s += 8) {
                    double d = ((p >> s) & 0xFF) - ((q >> s) & 0xFF);
                    mse += d * d;
                }
            }
        }
        mse /= 3.0 * r.width * r.height;
        return mse == 0 ? 99.99 : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    static Rectangle full(BufferedImage img) {
        return new Rectangle(img.getWidth(), img.getHeight());
    }

    static String decodeQr(BufferedImage img) {
        try {
            return new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))),
                    Map.of(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
                           DecodeHintType.TRY_HARDER, true)).getText();
        } catch (NotFoundException e) {
            return null;
        }
    }

    // ---------- codestream inspection ----------

    /** Index of the 2-byte marker FF xx, or -1. */
    static int indexOfMarker(byte[] data, int marker, int from, int to) {
        for (int i = from; i < Math.min(to, data.length) - 1; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == marker) return i;
        }
        return -1;
    }

    /**
     * True if the codestream signals max-shift ROI with an RGN marker (FF5E). The standard allows RGN
     * in the main header or in a tile-part header (JJ2000 uses the latter), so this walks the marker
     * segments of the main header and of the first tile-part header, up to the start of tile data
     * (SOD), instead of searching raw bytes (which could match inside compressed data).
     */
    static boolean hasRgnMarker(byte[] data) {
        int i = indexOfMarker(data, 0x4F, 0, data.length);   // SOC
        if (i < 0) return false;
        i += 2;
        boolean inTilePart = false;
        while (i + 4 <= data.length && (data[i] & 0xFF) == 0xFF) {
            int marker = data[i + 1] & 0xFF;
            if (marker == 0x93) return false;                   // SOD: header ended without RGN
            if (marker == 0x5E) return true;                    // RGN
            if (marker == 0x90) {
                if (inTilePart) return false;
                inTilePart = true;
            }
            int len = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            i += 2 + len;
        }
        return false;
    }

    static boolean isJp2(byte[] data) {
        // JP2 signature box: 00 00 00 0C 6A 50 20 20
        return data.length > 8 && data[4] == 'j' && data[5] == 'P' && data[6] == ' ' && data[7] == ' ';
    }

    static boolean isRawCodestream(byte[] data) {
        return data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F;
    }
}
