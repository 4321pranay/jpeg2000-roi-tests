package io.github._4321pranay.jpeg2000.roi;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github._4321pranay.jpeg2000.roi.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Decoding the ROI output with real decoders. OpenJPEG / Poppler tests are skipped when the tools are
 * not installed (opj_decompress, pdfimages).
 */
class RoiDecodingTest {

    /**
     * Measured on the 800x1000 test page: the 260x260 QR becomes bit-exact from about 0.25 bpp.
     * Below that the whole budget goes to the (unfinished) QR and the background is nearly empty.
     */
    private static final double BPP = 0.25;
    /** Background area (above the QR and photo block). */
    private static final Rectangle BACKGROUND = new Rectangle(0, 0, 800, 550);
    private static BufferedImage page;
    private static byte[] roiJp2;
    private static byte[] plainJp2;

    @BeforeAll
    static void encodeOnce() throws Exception {
        page = documentPage(BufferedImage.TYPE_BYTE_GRAY);
        RoiJ2KImageWriter w1 = roiWriter();
        roiJp2 = encode(w1, page, lossyParam(w1, BPP, QR_BOX));
        RoiJ2KImageWriter w2 = roiWriter();
        plainJp2 = encode(w2, page, lossyParam(w2, BPP));
    }

    @Test
    void openJpegDecodesRoiLosslesslyAndQrScans() throws Exception {
        assumeTrue(toolAvailable("opj_decompress"), "OpenJPEG not installed");
        BufferedImage dec = decodeWithOpenJpeg(roiJp2, ".jp2");

        assertEquals(99.99, psnr(page, dec, QR_BOX), "QR region should be bit-exact");
        assertEquals(QR_TEXT, decodeQr(dec));
        // background must be real image data (measured ~22.7 dB), not blank/grey (~11.8 dB)
        assertTrue(psnr(page, dec, BACKGROUND) > 18, "background looks lost");
    }

    @Test
    void roiBeatsPlainEncodingInsideQrAtSameRate() throws Exception {
        assumeTrue(toolAvailable("opj_decompress"), "OpenJPEG not installed");
        BufferedImage roi = decodeWithOpenJpeg(roiJp2, ".jp2");
        BufferedImage plain = decodeWithOpenJpeg(plainJp2, ".jp2");

        double qrRoi = psnr(page, roi, QR_BOX);
        double qrPlain = psnr(page, plain, QR_BOX);
        assertTrue(qrRoi > qrPlain + 10, "ROI QR " + qrRoi + " dB vs plain " + qrPlain + " dB");
        // the trade-off: background gets fewer bits with ROI
        assertTrue(psnr(page, roi, BACKGROUND) < psnr(page, plain, BACKGROUND));
    }

    @Test
    void popplerDecodesRoiImageEmbeddedInPdf(@TempDir Path dir) throws Exception {
        assumeTrue(toolAvailable("pdfimages"), "Poppler not installed");
        Path pdf = dir.resolve("roi.pdf");
        writePdfWithJpx(roiJp2, page.getWidth(), page.getHeight(), pdf.toFile());

        run("pdfimages", "-png", pdf.toString(), dir.resolve("img").toString());
        BufferedImage dec = ImageIO.read(dir.resolve("img-000.png").toFile());

        assertEquals(99.99, psnr(page, dec, QR_BOX), "QR region should be bit-exact");
        assertEquals(QR_TEXT, decodeQr(dec));
        assertTrue(psnr(page, dec, BACKGROUND) > 18, "background looks lost");
    }

    /**
     * Documents a known bug in jai-imageio's JPEG 2000 READER (not in this writer): the ROI is decoded
     * correctly but the background is not (it comes out at ~11.8 dB, i.e. essentially empty), for
     * lossy and even lossless ROI files. PDFBox uses this reader, so it renders ROI images wrongly too.
     * If this test starts failing after a library upgrade, the reader bug has been fixed: update the
     * test and the README.
     */
    @Test
    void knownBug_jaiImageioReaderLosesBackgroundOfRoiImages() throws Exception {
        BufferedImage dec = ImageIO.read(new ByteArrayInputStream(roiJp2));
        assertEquals(99.99, psnr(page, dec, QR_BOX), "ROI region decodes fine");
        assertEquals(QR_TEXT, decodeQr(dec));
        assertTrue(psnr(page, dec, BACKGROUND) < 14,
                "background decoded correctly: jai-imageio reader bug appears fixed");
    }

    @Test
    void knownBug_jaiImageioReaderFailsEvenOnLosslessRoiImages() throws Exception {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = (RoiJ2KImageWriteParam) w.getDefaultWriteParam();   // lossless
        p.addROI(QR_BOX);
        BufferedImage dec = ImageIO.read(new ByteArrayInputStream(encode(w, page, p)));
        assertEquals(99.99, psnr(page, dec, QR_BOX));
        assertTrue(psnr(page, dec, full(page)) < 14,
                "lossless ROI image decoded correctly: jai-imageio reader bug appears fixed");
    }

    @Test
    void openJpegDecodesLosslessRoiImageExactly() throws Exception {
        assumeTrue(toolAvailable("opj_decompress"), "OpenJPEG not installed");
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = (RoiJ2KImageWriteParam) w.getDefaultWriteParam();   // lossless
        p.addROI(QR_BOX);
        BufferedImage dec = decodeWithOpenJpeg(encode(w, page, p), ".jp2");
        assertEquals(99.99, psnr(page, dec, full(page)), "lossless + ROI must round-trip exactly");
    }

    private static void writePdfWithJpx(byte[] jp2, int w, int h, File out) throws Exception {
        float scale = 72f / 200f;
        try (PDDocument doc = new PDDocument()) {
            PDPage pg = new PDPage(new PDRectangle(w * scale, h * scale));
            doc.addPage(pg);
            PDStream s = new PDStream(doc, new ByteArrayInputStream(jp2));
            COSStream cos = s.getCOSObject();
            cos.setItem(COSName.FILTER, COSName.JPX_DECODE);
            cos.setItem(COSName.TYPE, COSName.XOBJECT);
            cos.setItem(COSName.SUBTYPE, COSName.IMAGE);
            cos.setInt(COSName.WIDTH, w);
            cos.setInt(COSName.HEIGHT, h);
            cos.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
            cos.setInt(COSName.BITS_PER_COMPONENT, 8);
            try (PDPageContentStream cs = new PDPageContentStream(doc, pg)) {
                cs.drawImage(new PDImageXObject(s, null), 0, 0, w * scale, h * scale);
            }
            doc.save(out);
        }
        assertTrue(Files.size(out.toPath()) > jp2.length);
    }
}
