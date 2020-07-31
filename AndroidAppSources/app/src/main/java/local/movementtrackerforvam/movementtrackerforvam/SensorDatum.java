package local.movementtrackerforvam.movementtrackerforvam;

public final class SensorDatum {

    public static final byte GYRO = 1;
    public static final byte ACCEL = 2;
    public static final byte GRAVITY = 3;
    public static final byte MAGNETIC = 4;

    public static final byte DOF6 = 11;

    public static final byte KEYS = 31;

    long timestamp;
    byte sensorType; // 1 for gyro, 2 for accelerator
    byte accuracy; // 1- low, 2- med, 3-high
    int payload;
    float [] data;
}
