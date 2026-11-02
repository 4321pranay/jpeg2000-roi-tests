package io.github._4321pranay.jpeg2000.roi;

import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static io.github._4321pranay.jpeg2000.roi.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Encoder behaviour that needs no external tools. Decoded quality is checked in RoiQualityTest. */
class RoiJ2KImageWriterTest {

    private static final BufferedImage GRAY = documentPage(BufferedImage.TYPE_BYTE_GRAY);

    /** Without ROIs every call is delegated, so output must match the standard writer byte for byte. */
    @Test
    void withoutRoisOutputIsIdenticalToStandardWriter() throws Exception {
        assertSameAsStandard(p -> { p.setLossless(false); p.setEncodingRate(0.3); });             // lossy, 9/7
        assertSameAsStandard(p -> { p.setLossless(false); p.setEncodingRate(0.3);
                                    p.setFilter(J2KImageWriteParam.FILTER_53); });                // lossy, 5/3
        assertSameAsStandard(p -> { });                                                           // lossless default
    }

    private static void assertSameAsStandard(java.util.function.Consumer<J2KImageWriteParam> settings)
            throws Exception {
        ImageWriter std = ImageIO.getImageWritersByFormatName("jpeg2000").next();
        J2KImageWriteParam sp = (J2KImageWriteParam) std.getDefaultWriteParam();
        settings.accept(sp);
        byte[] standard = encode(std, GRAY, sp);

        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = (RoiJ2KImageWriteParam) w.getDefaultWriteParam();
        settings.accept(p);
        byte[] ours = encode(w, GRAY, p);

        assertArrayEquals(standard, ours);
        assertFalse(hasRgnMarker(ours));
    }

    @Test
    void roiWritesRgnMarkerAndJp2Wrapper() throws Exception {
        RoiJ2KImageWriter w = roiWriter();
        byte[] data = encode(w, GRAY, lossyParam(w, 0.3, QR_BOX));
        assertTrue(isJp2(data), "expected a JP2 file");
        assertTrue(hasRgnMarker(data), "expected an RGN (ROI) marker");
    }

    @Test
    void codestreamOnlyOption() throws Exception {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
        p.setWriteCodeStreamOnly(true);
        byte[] data = encode(w, GRAY, p);
        assertTrue(isRawCodestream(data), "expected a raw J2K codestream");
        assertTrue(hasRgnMarker(data));
    }

    @Test
    void respectsTargetRate() throws Exception {
        double bpp = 0.2;
        RoiJ2KImageWriter w = roiWriter();
        byte[] data = encode(w, GRAY, lossyParam(w, bpp, QR_BOX));
        double budget = GRAY.getWidth() * GRAY.getHeight() * bpp / 8;
        assertTrue(data.length <= budget * 1.05 + 200,
                "size " + data.length + " exceeds rate budget " + (int) budget);
    }

    @Test
    void roiCostsAboutTheSameBytesAsPlain() throws Exception {
        RoiJ2KImageWriter w1 = roiWriter();
        byte[] plain = encode(w1, GRAY, lossyParam(w1, 0.2));
        RoiJ2KImageWriter w2 = roiWriter();
        byte[] roi = encode(w2, GRAY, lossyParam(w2, 0.2, QR_BOX));
        assertEquals(plain.length, roi.length, plain.length * 0.03);
    }

    @Test
    void multipleRois() throws Exception {
        RoiJ2KImageWriter w = roiWriter();
        byte[] data = encode(w, GRAY, lossyParam(w, 0.3, QR_BOX, new Rectangle(60, 60, 300, 40)));
        assertTrue(hasRgnMarker(data));
    }

    @Test
    void rgbImage() throws Exception {
        BufferedImage rgb = documentPage(BufferedImage.TYPE_3BYTE_BGR);
        RoiJ2KImageWriter w = roiWriter();
        byte[] data = encode(w, rgb, lossyParam(w, 0.3, QR_BOX));
        assertTrue(isJp2(data));
        assertTrue(hasRgnMarker(data));
    }

    @Test
    void losslessModeWithRoi() throws Exception {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = (RoiJ2KImageWriteParam) w.getDefaultWriteParam();
        p.addROI(QR_BOX);                              // default param = lossless
        byte[] data = encode(w, GRAY, p);
        assertTrue(isJp2(data));
        assertTrue(hasRgnMarker(data));
        // exact round trip is checked with OpenJPEG in RoiDecodingTest
    }

    @Test
    void tempFileIsRemovedWhenWritingFails() throws Exception {
        java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"));
        java.util.function.Supplier<Integer> count = () -> {
            String[] names = tmpDir.list((d, n) -> n.startsWith("jpeg2000-roi-"));
            return names == null ? 0 : names.length;
        };
        int before = count.get();

        RoiJ2KImageWriter w = roiWriter();
        javax.imageio.stream.ImageOutputStream failing = new javax.imageio.stream.ImageOutputStreamImpl() {
            @Override public void write(int b) throws java.io.IOException { throw new java.io.IOException("disk full"); }
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException { throw new java.io.IOException("disk full"); }
            @Override public int read() { return -1; }
            @Override public int read(byte[] b, int off, int len) { return -1; }
        };
        w.setOutput(failing);
        java.io.IOException e = assertThrows(java.io.IOException.class, () ->
                w.write(null, new javax.imageio.IIOImage(GRAY, null, null), lossyParam(w, 0.3, QR_BOX)));
        assertEquals("disk full", e.getMessage());
        assertEquals(before, count.get(), "temp file left behind");
    }

    @Test
    void roiOutsideImageIsRejected() {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, new Rectangle(700, 900, 200, 200));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> encode(w, GRAY, p));
        assertTrue(e.getMessage().contains("outside the image"));
    }

    @Test
    void outputNotSetIsRejected() {
        RoiJ2KImageWriter w = roiWriter();
        assertThrows(IllegalStateException.class, () ->
                w.write(null, new javax.imageio.IIOImage(GRAY, null, null), lossyParam(w, 0.3, QR_BOX)));
    }
}
