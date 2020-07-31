package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class AzimuthSetup extends AppCompatActivity implements SensorEventListener, View.OnClickListener{

    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private Sensor magneticSensor;

    private double gx,gy,gz;
    private double mx,my,mz;

    private int lastAngle = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_azimuth_setup);

        ((Button)findViewById(R.id.buttonUseThisDirection)).setOnClickListener(this);
    }


    @Override
    public void onClick(View v) {
        if(v.getId() == R.id.buttonUseThisDirection) {

            if(-1 != lastAngle) {
                Context context = MyApplication.getAppContext();
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                sp.edit().putInt("azimuth", lastAngle).apply();

                Toast.makeText(this, "Direction\nsaved", Toast.LENGTH_SHORT).show();
            }
        }
    }


    private double getDirectionFromNorth()
    {
        // D is a projection of north to the gravity plane
        double dp = mx * gz + my * gy + mz * gz;
        double Dx = mx - dp * gx;
        double Dy = my - dp * gy;
        double Dz = mz - dp * gz;
        double Dl = Math.sqrt(Dx * Dx + Dy * Dy + Dz * Dz);

        // now, lets project (0,sqrt(2),-sqrt(2)) to the gravity plane
        double sqrt2 = Math.sqrt(2);
        double dp2 = sqrt2*gy - sqrt2* gz;

        double Fx = - dp2 * gx;   // 0-(0*gx+sqrt2*gy-sqrt2*gz) * gx
        double Fy = sqrt2 - dp2* gy; // sqrt2-(0*gx+sqrt2*gy-sqrt2*gz) * gy
        double Fz = -sqrt2 - dp2* gz; // -sqrt2-(0*gx+sqrt2*gy-sqrt2*gz) * gz
        double Fl = Math.sqrt(Fx * Fx + Fy * Fy + Fz * Fz);

        // angle
        double angle = Math.acos((Dx * Fx + Dy * Fy + Dz * Fz) / (Fl * Dl));
        angle *= 180.0 / 3.1415;

        // here I determine orientation of D, F with normal g
        double CPx = Dy*Fz - Dz*Fy;
        double CPy = Dz*Fx - Dx*Fz;
        double CPz = Dx*Fy - Dy*Fx;
        double dp3 = CPx*gx + CPy*gy + CPz*gz;

        if(dp3 > 0)
            angle = 360-angle;

        return angle;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(event.sensor== gravitySensor)
        {
            gx = event.values[0];
            gy = event.values[1];
            gz = event.values[2];

            double l = Math.sqrt(gx*gx + gy*gy + gz*gz);
            gx /= l;
            gy /= l;
            gz /= l;
        }


        if(event.sensor == magneticSensor)
        {
            TextView tv = (TextView)findViewById(R.id.directionMessage);
            if(null != tv) {
                mx = event.values[0];
                my = event.values[1];
                mz = event.values[2];

                int angle = (int)(0.5 + getDirectionFromNorth());
                if(angle >= 360) angle -= 360;
                if(angle < 0) angle += 360;

                lastAngle = angle;

                tv.setText("Magnetic direction: " + angle);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

        if(sensor == magneticSensor)
        {
            TextView tv = (TextView)findViewById(R.id.calibrationMessage);
            if( null != tv ) {

                if (accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH) {
                    tv.setText("Sensor is calibrated properly (3/3)");
                    tv.setTextColor(Color.rgb(0,128,0));
                } else {
                    tv.setText("Magnetometer is not calibrated (" + accuracy + "/3). Swing you phone");
                    tv.setTextColor(Color.rgb(0,0,128));
                }
            }
        }

    }

    @Override
    protected void onStart() {
        super.onStart();

        if(null == sensorManager) {
            sensorManager = (SensorManager) MyApplication.getAppContext().getSystemService(Context.SENSOR_SERVICE);
        }

        if(null == magneticSensor) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        if(null == gravitySensor) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }

        sensorManager.registerListener(this,
                magneticSensor,
                SensorManager.SENSOR_DELAY_FASTEST
        );
        sensorManager.registerListener(this,
                gravitySensor,
                SensorManager.SENSOR_DELAY_FASTEST
        );

    }

    @Override
    protected void onStop() {
        super.onStop();

        if(sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }
}
