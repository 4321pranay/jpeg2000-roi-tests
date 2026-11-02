package io.github._4321pranay.jpeg2000.roi;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageWriteParam;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static io.github._4321pranay.jpeg2000.roi.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decodes the output with OpenJPEG and checks quality inside each ROI, so options are verified by
 * their effect on the image rather than only by the presence of the RGN marker. Thresholds come from
 * measurements on the 800x1000 test page (see comments).
 */
class RoiQualityTest {

    private static final Rectangle BACKGROUND = new Rectangle(0, 0, 800, 550);
    private static final Rectangle TEXT_ROI = new Rectangle(60, 60, 300, 40);
    private static BufferedImage gray;

    @BeforeAll
    static void setUp() {
        assumeTrue(toolAvailable("opj_decompress"), "OpenJPEG not installed");
        gray = documentPage(BufferedImage.TYPE_BYTE_GRAY);
    }

    private static BufferedImage roundTrip(BufferedImage img, RoiJ2KImageWriteParam p, RoiJ2KImageWriter w,
                                           String suffix) throws Exception {
        return decodeWithOpenJpeg(encode(w, img, p), suffix);
    }

    @Test
    void everyRoiIsExactWhenSeveralAreGiven() throws Exception {
        // measured at 0.30 bpp: QR exact, text box exact, rest 21.1 dB
        RoiJ2KImageWriter w = roiWriter();
        BufferedImage d = roundTrip(gray, lossyParam(w, 0.3, QR_BOX, TEXT_ROI), w, ".jp2");
        assertEquals(99.99, psnr(gray, d, QR_BOX), "first ROI");
        assertEquals(99.99, psnr(gray, d, TEXT_ROI), "second ROI");
        assertTrue(psnr(gray, d, new Rectangle(0, 150, 800, 400)) > 18, "background still gets bits");
    }

    @Test
    void rgbRoiIsExactAndScans() throws Exception {
        // measured at 0.25 bpp: QR exact (all three channels), background 19.2 dB
        BufferedImage rgb = documentPage(BufferedImage.TYPE_3BYTE_BGR);
        RoiJ2KImageWriter w = roiWriter();
        BufferedImage d = roundTrip(rgb, lossyParam(w, 0.25, QR_BOX), w, ".jp2");
        assertEquals(99.99, psnr(rgb, d, QR_BOX));
        assertEquals(QR_TEXT, decodeQr(d));
        assertTrue(psnr(rgb, d, BACKGROUND) > 16);
    }

    @Test
    void rawCodestreamDecodesWithRoiExact() throws Exception {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
        p.setWriteCodeStreamOnly(true);
        BufferedImage d = roundTrip(gray, p, w, ".j2k");
        assertEquals(99.99, psnr(gray, d, QR_BOX));
        assertTrue(psnr(gray, d, BACKGROUND) > 20);              // measured 24.1 dB
    }

    @Test
    void tiledEncodingKeepsRoiExact() throws Exception {
        // QR_BOX (500..760, 600..860) spans several 256x256 tiles
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
        p.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
        p.setTiling(256, 256, 0, 0);
        byte[] data = encode(w, gray, p);
        assertTrue(hasRgnMarker(data));
        BufferedImage d = decodeWithOpenJpeg(data, ".jp2");
        assertEquals(99.99, psnr(gray, d, QR_BOX));
        assertTrue(psnr(gray, d, BACKGROUND) > 20);              // measured 23.7 dB
    }

    @Test
    void startLevelShiftsBitsFromRoiToBackgroundAtLowRates() throws Exception {
        // measured at 0.15 bpp: level -1 -> QR 43.3 / bg 17.9 dB; level 3 -> QR 23.2 / bg 19.7 dB
        RoiJ2KImageWriter w1 = roiWriter();
        BufferedImage normal = roundTrip(gray, lossyParam(w1, 0.15, QR_BOX), w1, ".jp2");
        RoiJ2KImageWriter w2 = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w2, 0.15, QR_BOX);
        p.setROIStartLevel(3);
        BufferedImage early = roundTrip(gray, p, w2, ".jp2");

        assertTrue(psnr(gray, early, BACKGROUND) > psnr(gray, normal, BACKGROUND) + 1, "background should improve");
        assertTrue(psnr(gray, early, QR_BOX) < psnr(gray, normal, QR_BOX) - 10, "QR should lose priority");
    }

    @Test
    void startLevelStillGivesExactRoiWhenTheBudgetAllows() throws Exception {
        // measured at 0.40 bpp: QR exact for every start level from -1 to 3
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.4, QR_BOX);
        p.setROIStartLevel(2);
        assertEquals(99.99, psnr(gray, roundTrip(gray, p, w, ".jp2"), QR_BOX));
    }

    @Test
    void codeBlockAlignmentEnlargesTheProtectedArea() throws Exception {
        // aligned ROI covers whole 64x64 code-blocks -> more data to protect.
        // measured at 0.25 bpp: unaligned QR exact; aligned QR 31.0 dB
        RoiJ2KImageWriter w1 = roiWriter();
        BufferedImage exact = roundTrip(gray, lossyParam(w1, 0.25, QR_BOX), w1, ".jp2");
        RoiJ2KImageWriter w2 = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w2, 0.25, QR_BOX);
        p.setROIAlignedToCodeBlocks(true);
        BufferedImage aligned = roundTrip(gray, p, w2, ".jp2");

        assertEquals(99.99, psnr(gray, exact, QR_BOX));
        assertTrue(psnr(gray, aligned, QR_BOX) < 99.99, "aligned ROI needs a bigger budget");
        assertNotNull(decodeQr(aligned), "but the QR must still scan");
    }
}
