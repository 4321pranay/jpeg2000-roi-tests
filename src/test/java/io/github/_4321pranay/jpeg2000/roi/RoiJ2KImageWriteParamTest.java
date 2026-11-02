package io.github._4321pranay.jpeg2000.roi;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoiJ2KImageWriteParamTest {

    @Test
    void noRoisByDefault() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        assertFalse(p.hasROIs());
        assertTrue(p.getROIs().isEmpty());
        assertFalse(p.getROIAlignedToCodeBlocks());
        assertEquals(-1, p.getROIStartLevel());
    }

    @Test
    void addRoiStoresACopy() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        Rectangle r = new Rectangle(10, 20, 30, 40);
        p.addROI(r);
        r.x = 999;                                        // caller mutates its own rectangle
        assertEquals(List.of(new Rectangle(10, 20, 30, 40)), p.getROIs());
    }

    @Test
    void getRoisCannotModifyParam() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        p.addROI(new Rectangle(0, 0, 5, 5));
        assertThrows(UnsupportedOperationException.class, () -> p.getROIs().clear());
        p.getROIs().get(0).width = 999;
        assertEquals(5, p.getROIs().get(0).width);
    }

    @Test
    void rejectsInvalidRois() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        assertThrows(IllegalArgumentException.class, () -> p.addROI(null));
        assertThrows(IllegalArgumentException.class, () -> p.addROI(new Rectangle(0, 0, 0, 10)));
        assertThrows(IllegalArgumentException.class, () -> p.addROI(new Rectangle(-1, 0, 10, 10)));
        assertFalse(p.hasROIs());
    }

    @Test
    void setRoisReplacesAndClears() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        p.addROI(new Rectangle(1, 1, 1, 1));
        p.setROIs(List.of(new Rectangle(2, 2, 2, 2), new Rectangle(3, 3, 3, 3)));
        assertEquals(2, p.getROIs().size());
        p.setROIs(null);
        assertFalse(p.hasROIs());
    }

    @Test
    void setRoisIsAllOrNothing() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        p.addROI(new Rectangle(1, 1, 1, 1));
        List<Rectangle> bad = new ArrayList<>(Arrays.asList(new Rectangle(2, 2, 2, 2), null));
        assertThrows(IllegalArgumentException.class, () -> p.setROIs(bad));
        assertEquals(List.of(new Rectangle(1, 1, 1, 1)), p.getROIs());   // unchanged
    }

    @Test
    void startLevelValidation() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        p.setROIStartLevel(0);
        assertEquals(0, p.getROIStartLevel());
        assertThrows(IllegalArgumentException.class, () -> p.setROIStartLevel(-2));
    }

    @Test
    void jj2000SpecFormat() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        p.addROI(new Rectangle(1, 2, 3, 4));
        p.addROI(new Rectangle(5, 6, 7, 8));
        assertEquals("R 1 2 3 4 R 5 6 7 8", p.toJJ2000Spec());
    }

    @Test
    void standardSettingsStillWork() {
        RoiJ2KImageWriteParam p = new RoiJ2KImageWriteParam();
        p.setLossless(false);
        p.setEncodingRate(0.25);
        p.setFilter(RoiJ2KImageWriteParam.FILTER_97);
        assertEquals(0.25, p.getEncodingRate());
        assertEquals(RoiJ2KImageWriteParam.FILTER_97, p.getFilter());
    }
}
