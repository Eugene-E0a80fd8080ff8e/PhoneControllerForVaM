package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

public class SensorThread {

    private static SensorManager sensorManager;
    private static Sensor gyroscopeSensor;
    private static Sensor accelSensor;
    private static Sensor magneticSensor;
    private static Sensor gravitySensor;

    //private static Sensor dof6Sensor;

    private HandlerThread mHandlerThread;

    private SensorEventListener gyroscopeSensorEventListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorDatum sd = new SensorDatum();
            sd.sensorType = SensorDatum.GYRO;
            sd.accuracy = (byte)event.accuracy;
            sd.timestamp = event.timestamp;
            sd.data = event.values.clone();

            MyApplication.sensorDataPipe.add(sd);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            StateManager.update( state -> { state.gyroscopeCalibration=accuracy; return state; } );

        }
    };

    private SensorEventListener accelSensorEventListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorDatum sd = new SensorDatum();
            sd.sensorType = SensorDatum.ACCEL;
            sd.accuracy = (byte)event.accuracy;
            sd.timestamp = event.timestamp;
            sd.data = event.values.clone();

            MyApplication.sensorDataPipe.add(sd);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            StateManager.update( state -> { state.accelerometerCalibration=accuracy; return state; } );

        }
    };

    private SensorEventListener magneticSensorEventListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorDatum sd = new SensorDatum();
            sd.sensorType = SensorDatum.MAGNETIC;
            sd.accuracy = (byte)event.accuracy;
            sd.timestamp = event.timestamp;
            sd.data = event.values.clone();

            MyApplication.sensorDataPipe.add(sd);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            StateManager.update( state -> { state.magnetometerCalibration=accuracy; return state; } );
        }
    };

    private SensorEventListener gravitySensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            SensorDatum sd = new SensorDatum();
            sd.sensorType = SensorDatum.GRAVITY;
            sd.accuracy = (byte)event.accuracy;
            sd.timestamp = event.timestamp;
            sd.data = event.values.clone();

            MyApplication.sensorDataPipe.add(sd);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    };

    public SensorThread()
    {

    }

    public void start()
    {
        if(null == sensorManager) {
            sensorManager = (SensorManager) MyApplication.getAppContext().getSystemService(Context.SENSOR_SERVICE);
        }

        if(null == gyroscopeSensor) {
            gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        if(null == accelSensor) {
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        }

        if(null == magneticSensor) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        if(null == gravitySensor) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }

        mHandlerThread = new HandlerThread("Sensor Thread");
        mHandlerThread.start();
        Handler handler = new Handler(mHandlerThread.getLooper());

        sensorManager.registerListener(gyroscopeSensorEventListener,
                gyroscopeSensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
        );

        sensorManager.registerListener(accelSensorEventListener,
                accelSensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
        );

        sensorManager.registerListener(magneticSensorEventListener,
                magneticSensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
        );
        sensorManager.registerListener(gravitySensorEventListener,
                gravitySensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
        );

    }

    public void stop()
    {
        if(sensorManager != null) {
            sensorManager.unregisterListener(gyroscopeSensorEventListener);
            sensorManager.unregisterListener(accelSensorEventListener);
            sensorManager.unregisterListener(gravitySensorEventListener);
            sensorManager.unregisterListener(magneticSensorEventListener);
        }

        if(mHandlerThread.isAlive())
            mHandlerThread.quitSafely();
    }

}
