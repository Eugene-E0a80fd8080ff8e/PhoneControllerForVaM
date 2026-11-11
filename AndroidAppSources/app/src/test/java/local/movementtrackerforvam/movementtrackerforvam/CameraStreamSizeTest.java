package local.movementtrackerforvam.movementtrackerforvam;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CameraStreamSizeTest {
    @Test public void allPresetsFitTheViewportAndVaMSizeLimits() {
        for (int resolution : new int[]{240, 360, 480, 560, 640, 720}) {
            for (int height : new int[]{1080, 1400, 1800, 2200, 3200}) {
                int[] size = CameraStreamSize.forViewport(1080, height, resolution);
                double expectedAspect = Math.max(.35, 1080.0 / height);
                double pixelBudget = resolution * resolution * (16.0 / 9.0);
                assertEquals(expectedAspect, size[0] / (double) size[1], .004);
                assertEquals(pixelBudget, size[0] * size[1], pixelBudget * .015);
                assertEquals(0, size[0] % 2);
                assertEquals(0, size[1] % 2);
                assertTrue(size[0] >= 128 && size[1] >= 128);
                assertTrue(size[0] <= 2048 && size[1] <= 2048);
                assertTrue(size[0] * size[1] <= 1000000);
            }
        }
    }

    @Test public void unmeasuredViewportUsesPortraitFallbackAtTheSelectedResolution() {
        assertArrayEquals(new int[]{360, 640}, CameraStreamSize.forViewport(0, 0, 360));
        assertArrayEquals(new int[]{720, 1280}, CameraStreamSize.forViewport(0, 0, 720));
    }

    @Test public void unknownResolutionUsesThe360pDefault() {
        assertArrayEquals(CameraStreamSize.forViewport(1080, 1800, 360),
                CameraStreamSize.forViewport(1080, 1800, 900));
    }
}
