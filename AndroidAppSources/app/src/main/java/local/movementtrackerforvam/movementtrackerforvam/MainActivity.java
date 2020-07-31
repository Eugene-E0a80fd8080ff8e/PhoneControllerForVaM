package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity implements StateManager.StateListener
,SharedPreferences.OnSharedPreferenceChangeListener {

    SensorThread sensor = null;
    CommunicationThread communication = null;

    private int buttonsPressed = 0;

    ColorStateList csl_normal = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ////////////////

        int[] buttons = new int[] {
                R.id.buttonEngageHead,
                R.id.buttonEngageNeck,

                R.id.buttonEngageLArm,
                R.id.buttonEngageRArm,
                R.id.buttonEngageLElbow,
                R.id.buttonEngageRElbow,
                R.id.buttonEngageLHand,
                R.id.buttonEngageRHand,

                R.id.buttonEngageChest,
                R.id.buttonEngageHip,

                R.id.buttonEngageLThigh,
                R.id.buttonEngageRThigh,
                R.id.buttonEngageLKnee,
                R.id.buttonEngageRKnee,
                R.id.buttonEngageLFoot,
                R.id.buttonEngageRFoot
        };

        for(int q : buttons)
            ((Button)findViewById(q)).setOnTouchListener(btnTouch);

        ((Switch)findViewById(R.id.switchMoveSweeps)).setOnCheckedChangeListener(switchListener);

        {
            Context context = MyApplication.getAppContext();
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            sp.registerOnSharedPreferenceChangeListener(this);
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        {
            Context context = MyApplication.getAppContext();
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            sp.unregisterOnSharedPreferenceChangeListener(this);
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater mi = getMenuInflater();
        mi.inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if( R.id.invoke_settings_page == item.getItemId() )
        {
            //Toast.makeText(this, "Settings page", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);

            return true;
        }
        if( R.id.invoke_azimuth_setup_page == item.getItemId() )
        {
            //Toast.makeText(this, "Settings page", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, AzimuthSetup.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        StateManager.addStateListener(this , true);

        updateAzimuthMessage();

        if( null == sensor) sensor = new SensorThread();
        if( null == communication ) communication = new CommunicationThread();
        sensor.start();
        communication.start();
    }

    @Override
    protected void onStop() {
        super.onStop();

        //StateManager.update( (State s) -> { s.isTransmissionOn = false; return s;  });
        StateManager.removeStateListener(this,false);
        sensor.stop();
        communication.stop();
        MyApplication.sensorDataPipe.clear();
        MyApplication.keysSensorData = null;
    }

    @Override
    public void stateListener(State newState, State oldState) {

        State state = StateManager.get();

        if(state.magnetometerCalibration != SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        {
            String s = "Magnetometer is not calibrated " + state.magnetometerCalibration + "/3";
            this.runOnUiThread( () -> {
                TextView tv = (TextView)findViewById(R.id.sensorStatusText);
                tv.setText(s);
                tv.setVisibility(View.VISIBLE);
            });
        }
        else if(state.accelerometerCalibration != SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        {
            String s = "Accelerometer is not calibrated " + state.accelerometerCalibration + "/3";
            this.runOnUiThread( () -> {
                TextView tv = (TextView)findViewById(R.id.sensorStatusText);
                tv.setText(s);
                tv.setVisibility(View.VISIBLE);
            });
        }
        else if(state.gyroscopeCalibration != SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        {
            String s = "Gyroscope is not calibrated " + state.gyroscopeCalibration + "/3";
            this.runOnUiThread( () -> {
                TextView tv = (TextView)findViewById(R.id.sensorStatusText);
                tv.setText(s);
                tv.setVisibility(View.VISIBLE);
            });
        }
        else
        {
            this.runOnUiThread( () -> {
                TextView tv = (TextView)findViewById(R.id.sensorStatusText);
                tv.setText("");
                tv.setVisibility(View.INVISIBLE);
            });
        }

        if( ! oldState.clientsList.equals(newState.clientsList)) {
            this.runOnUiThread( () -> {
                TextView tv = (TextView)findViewById(R.id.connectedToText);
                if( "".equals(newState.clientsList) )
                    tv.setText("Not connected");
                else
                    tv.setText("Connected to: " + newState.clientsList);
                tv.setVisibility(View.VISIBLE);
            });
        }

    }
/*
    public void toggleStartStop(View view)
    {
        //Toast.makeText(this, "clicked", Toast.LENGTH_SHORT).show();
        Context context = MyApplication.getAppContext();

        StateManager.update( (State s) -> { s.isTransmissionOn = !s.isTransmissionOn; return s;  });
    }
    */

    private void sendKeyboardState()
    {
        SensorDatum sd = new SensorDatum();
        sd.sensorType = SensorDatum.KEYS;
        sd.timestamp = -1; // currentTimeMillis would be incorrect here, better send nothing
        sd.payload = buttonsPressed;

        if( null == MyApplication.keysSensorData)
            MyApplication.keysSensorData = sd;
        else
            synchronized( MyApplication.keysSensorData )
            {
                MyApplication.keysSensorData = sd;
            }
    }

    private Switch.OnCheckedChangeListener switchListener = new Switch.OnCheckedChangeListener () {
        @Override
        public void onCheckedChanged(CompoundButton v, boolean isChecked) {
            Switch sw = (Switch)v;
            if( sw.getId() == R.id.switchMoveSweeps )
            {
                if(isChecked) buttonsPressed|=1<<30;
                else buttonsPressed&=~(1<<30);
                sendKeyboardState();

                //////////////////////
                if(null == csl_normal)
                    csl_normal = ((Button)findViewById(R.id.buttonEngageLHand)).getBackgroundTintList();

                if(isChecked) {
                    ((Button)findViewById(R.id.buttonEngageLHand)).setBackgroundTintList(MyApplication.getAppContext().getResources().getColorStateList(R.color.colorPink));
                    ((Button)findViewById(R.id.buttonEngageRHand)).setBackgroundTintList(MyApplication.getAppContext().getResources().getColorStateList(R.color.colorPink));
                    ((Button)findViewById(R.id.buttonEngageLFoot)).setBackgroundTintList(MyApplication.getAppContext().getResources().getColorStateList(R.color.colorPink));
                    ((Button)findViewById(R.id.buttonEngageRFoot)).setBackgroundTintList(MyApplication.getAppContext().getResources().getColorStateList(R.color.colorPink));
                }else{
                    ((Button)findViewById(R.id.buttonEngageLHand)).setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d8d8d8")));
                    ((Button)findViewById(R.id.buttonEngageRHand)).setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d8d8d8")));
                    ((Button)findViewById(R.id.buttonEngageLFoot)).setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d8d8d8")));
                    ((Button)findViewById(R.id.buttonEngageRFoot)).setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d8d8d8")));

                }
            }
        }
    };

    private View.OnTouchListener btnTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Button b = (Button)v;
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN)
            {
                switch( b.getId() ) {
                    case R.id.buttonEngageHead: buttonsPressed|=1; break;
                    case R.id.buttonEngageNeck: buttonsPressed|=2; break;

                    case R.id.buttonEngageLHand: buttonsPressed|=1<<8; break;
                    case R.id.buttonEngageRHand: buttonsPressed|=1<<9; break;
                    case R.id.buttonEngageLElbow: buttonsPressed|=1<<10; break;
                    case R.id.buttonEngageRElbow: buttonsPressed|=1<<11; break;
                    case R.id.buttonEngageLArm: buttonsPressed|=1<<12; break;
                    case R.id.buttonEngageRArm: buttonsPressed|=1<<13; break;

                    case R.id.buttonEngageChest: buttonsPressed|=1<<17; break;
                    case R.id.buttonEngageHip: buttonsPressed|=1<<16; break;

                    case R.id.buttonEngageLThigh: buttonsPressed|=1<<28; break;
                    case R.id.buttonEngageRThigh: buttonsPressed|=1<<29; break;
                    case R.id.buttonEngageLKnee: buttonsPressed|=1<<26; break;
                    case R.id.buttonEngageRKnee: buttonsPressed|=1<<27; break;
                    case R.id.buttonEngageLFoot: buttonsPressed|=1<<24; break;
                    case R.id.buttonEngageRFoot: buttonsPressed|=1<<25; break;
                }

                sendKeyboardState();

                //System.out.println("flags : " + fHead + "," + fLHand + "," + fRHand + "," + fHip + "," + fLFoot + "," + fRFoot);
                return true;
            }
            else
            if (action == MotionEvent.ACTION_UP) {
                switch( b.getId() ) {
                    case R.id.buttonEngageHead: buttonsPressed&=~1; break;
                    case R.id.buttonEngageNeck: buttonsPressed&=~2; break;

                    case R.id.buttonEngageLHand: buttonsPressed&=~(1<<8); break;
                    case R.id.buttonEngageRHand: buttonsPressed&=~(1<<9); break;
                    case R.id.buttonEngageLElbow: buttonsPressed&=~(1<<10); break;
                    case R.id.buttonEngageRElbow: buttonsPressed&=~(1<<11); break;
                    case R.id.buttonEngageLArm: buttonsPressed&=~(1<<12); break;
                    case R.id.buttonEngageRArm: buttonsPressed&=~(1<<13); break;

                    case R.id.buttonEngageChest: buttonsPressed&=~(1<<17); break;
                    case R.id.buttonEngageHip: buttonsPressed&=~(1<<16); break;

                    case R.id.buttonEngageLThigh: buttonsPressed&=~(1<<28); break;
                    case R.id.buttonEngageRThigh: buttonsPressed&=~(1<<29); break;
                    case R.id.buttonEngageLKnee: buttonsPressed&=~(1<<26); break;
                    case R.id.buttonEngageRKnee: buttonsPressed&=~(1<<27); break;
                    case R.id.buttonEngageLFoot: buttonsPressed&=~(1<<24); break;
                    case R.id.buttonEngageRFoot: buttonsPressed&=~(1<<25); break;
                }

                sendKeyboardState();

                //System.out.println("flags : " + fHead + "," + fLHand + "," + fRHand + "," + fHip + "," + fLFoot + "," + fRFoot);
                return true;
            }

            return false;   //  the listener has NOT consumed the event, pass it on
        }


    };

    private void updateAzimuthMessage()
    {
        Context context = MyApplication.getAppContext();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        if(! sp.contains("azimuth") )
        {
            ((TextView)findViewById(R.id.pleaseSetDirection)).setVisibility(View.VISIBLE);
        }else{
            ((TextView)findViewById(R.id.pleaseSetDirection)).setVisibility(View.INVISIBLE);
        }

    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updateAzimuthMessage();
    }
}
