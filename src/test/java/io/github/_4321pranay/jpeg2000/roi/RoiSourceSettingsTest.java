package io.github._4321pranay.jpeg2000.roi;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github._4321pranay.jpeg2000.roi.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ROIs are in full-image coordinates, so they must not be combined with a source region (crop) or
 * subsampling (shrink): the regions would not line up and JJ2000 silently ignores them.
 */
class RoiSourceSettingsTest {

    private static final BufferedImage PAGE = documentPage(BufferedImage.TYPE_BYTE_GRAY);  // 800x1000

    @Test
    void sourceRegionCropIsRejected() {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
        p.setSourceRegion(new Rectangle(0, 500, 800, 500));                  // bottom half
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> encode(w, PAGE, p));
        assertTrue(e.getMessage().contains("source region"), e.getMessage());
    }

    @Test
    void subsamplingIsRejected() {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
        p.setSourceSubsampling(2, 2, 0, 0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> encode(w, PAGE, p));
        assertTrue(e.getMessage().contains("subsampling"), e.getMessage());
    }

    @Test
    void subsamplingInOneDirectionIsRejected() {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
        p.setSourceSubsampling(1, 2, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> encode(w, PAGE, p));
    }

    @Test
    void sourceRegionCoveringWholeImageIsAllowed() throws Exception {
        for (Rectangle region : new Rectangle[] {new Rectangle(0, 0, 800, 1000), new Rectangle(0, 0, 5000, 5000)}) {
            RoiJ2KImageWriter w = roiWriter();
            RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
            p.setSourceRegion(region);                                         // no actual crop
            byte[] data = encode(w, PAGE, p);
            assertTrue(hasRgnMarker(data), "region " + region);
        }
    }

    @Test
    void cropAndSubsamplingStillWorkWithoutRois() throws Exception {
        // no ROIs -> delegated to the standard writer, which supports these settings
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3);
        p.setSourceRegion(new Rectangle(0, 500, 800, 500));
        p.setSourceSubsampling(2, 2, 0, 0);
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(encode(w, PAGE, p)));
        assertEquals(400, back.getWidth());
        assertEquals(250, back.getHeight());
    }

    @Test
    void rejectedBeforeAnythingIsWrittenOrReported() throws Exception {
        RoiJ2KImageWriter w = roiWriter();
        RoiJ2KImageWriteParam p = lossyParam(w, 0.3, QR_BOX);
        p.setSourceSubsampling(2, 2, 0, 0);

        AtomicBoolean started = new AtomicBoolean();
        w.addIIOWriteProgressListener(new IIOWriteProgressListener() {
            public void imageStarted(ImageWriter source, int imageIndex) { started.set(true); }
            public void imageProgress(ImageWriter source, float percentageDone) {}
            public void imageComplete(ImageWriter source) {}
            public void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex) {}
            public void thumbnailProgress(ImageWriter source, float percentageDone) {}
            public void thumbnailComplete(ImageWriter source) {}
            public void writeAborted(ImageWriter source) {}
        });

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            w.setOutput(ios);
            assertThrows(IllegalArgumentException.class,
                    () -> w.write(null, new IIOImage(PAGE, null, null), p));
        }
        assertEquals(0, bos.size(), "nothing should be written");
        assertFalse(started.get(), "imageStarted should not be reported for rejected input");
    }
}
