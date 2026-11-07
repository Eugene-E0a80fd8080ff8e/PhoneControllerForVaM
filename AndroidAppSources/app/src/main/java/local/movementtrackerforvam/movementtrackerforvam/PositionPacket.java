package local.movementtrackerforvam.movementtrackerforvam;

import java.nio.ByteBuffer;

/** Position record v1, little-endian. Appended after legacy records. */
final class PositionPacket {
    static final int TYPE = 33;
    static final int BYTES = 31;

    static void write(ByteBuffer buffer, boolean tracking, long reference, long sequence,
                      float x, float y, float z) {
        buffer.put((byte) TYPE).put((byte) 1).put((byte) (tracking ? 1 : 0));
        buffer.putLong(tracking ? reference : 0L).putLong(sequence);
        buffer.putFloat(tracking ? x : 0f).putFloat(tracking ? y : 0f).putFloat(tracking ? z : 0f);
    }
}
