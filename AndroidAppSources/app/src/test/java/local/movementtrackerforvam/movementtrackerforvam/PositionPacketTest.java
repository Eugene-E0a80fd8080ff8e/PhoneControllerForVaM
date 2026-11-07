package local.movementtrackerforvam.movementtrackerforvam;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class PositionPacketTest {
    @Test public void trackingRecordUsesDocumentedLayout() {
        ByteBuffer b = ByteBuffer.allocate(PositionPacket.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        PositionPacket.write(b, true, 0x0102030405060708L, 42, 1.5f, -2f, 0.25f);
        assertEquals(31, b.position());
        assertEquals(33, b.get(0)); assertEquals(1, b.get(1)); assertEquals(1, b.get(2));
        assertEquals(0x0102030405060708L, b.getLong(3));
        assertEquals(42, b.getLong(11));
        assertEquals(1.5f, b.getFloat(19), 0f);
        assertEquals(-2f, b.getFloat(23), 0f);
        assertEquals(0.25f, b.getFloat(27), 0f);
        assertEquals(8, b.get(3)); // Little-endian reference.
    }
    @Test public void lostTrackingCannotCarryAnOldPose() {
        ByteBuffer b = ByteBuffer.allocate(PositionPacket.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        PositionPacket.write(b, false, 9, 17, 1, 2, 3);
        assertEquals(0, b.get(2)); assertEquals(0, b.getLong(3));
        assertEquals(17, b.getLong(11));
        assertEquals(0f, b.getFloat(19), 0f);
        assertEquals(0f, b.getFloat(23), 0f);
        assertEquals(0f, b.getFloat(27), 0f);
    }
}
