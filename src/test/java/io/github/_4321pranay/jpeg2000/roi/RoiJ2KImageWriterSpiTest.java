package io.github._4321pranay.jpeg2000.roi;

import com.github.jaiimageio.jpeg2000.impl.J2KImageWriter;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import java.awt.image.BufferedImage;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class RoiJ2KImageWriterSpiTest {

    @Test
    void registeredUnderOwnFormatName() {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg2000-roi");
        assertTrue(it.hasNext(), "writer not registered via META-INF/services");
        assertInstanceOf(RoiJ2KImageWriter.class, it.next());
    }

    @Test
    void defaultParamIsRoiParam() {
        assertInstanceOf(RoiJ2KImageWriteParam.class, TestSupport.roiWriter().getDefaultWriteParam());
    }

    @Test
    void doesNotShadowStandardWriter() {
        // lookups by the standard name, suffix and MIME type must still give the original writer only
        for (Iterator<ImageWriter> it : java.util.List.of(
                ImageIO.getImageWritersByFormatName("jpeg2000"),
                ImageIO.getImageWritersBySuffix("jp2"),
                ImageIO.getImageWritersByMIMEType("image/jp2"))) {
            assertTrue(it.hasNext());
            while (it.hasNext()) {
                ImageWriter w = it.next();
                assertFalse(w instanceof RoiJ2KImageWriter, "ROI writer shadows standard lookup");
                assertInstanceOf(J2KImageWriter.class, w);
            }
        }
    }

    @Test
    void canEncodeSameTypesAsStandardWriter() {
        RoiJ2KImageWriterSpi spi = new RoiJ2KImageWriterSpi();
        assertTrue(spi.canEncodeImage(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_GRAY)));
        assertTrue(spi.canEncodeImage(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_3BYTE_BGR)));
        assertTrue(spi.canEncodeImage(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)));
    }
}
