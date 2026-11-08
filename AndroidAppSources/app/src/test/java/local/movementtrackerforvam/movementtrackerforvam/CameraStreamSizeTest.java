package local.movementtrackerforvam.movementtrackerforvam;
import org.junit.Test;
import static org.junit.Assert.*;
public class CameraStreamSizeTest {
    @Test public void matchesPortraitViewportWithBoundedPixelCount() {
        for (int height : new int[]{1400, 1800, 2200}) {
            int[] size = CameraStreamSize.forViewport(1080, height);
            assertEquals(1080.0/height, size[0]/(double)size[1], .004);
            assertTrue(size[0]*size[1] <= 233000);
            assertEquals(0, size[0]%2); assertEquals(0, size[1]%2);
            assertTrue(size[0] <= 1024 && size[1] <= 1024);
        }
    }
    @Test public void unmeasuredViewportUsesPortraitFallback() {
        assertArrayEquals(new int[]{360,640}, CameraStreamSize.forViewport(0,0));
    }
}
