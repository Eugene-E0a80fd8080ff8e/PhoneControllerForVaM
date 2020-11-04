package local.movementtrackerforvam.movementtrackerforvam;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.preference.Preference;
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

import java.util.TreeMap;

public class MainActivity extends AppCompatActivity implements StateManager.StateListener
,SharedPreferences.OnSharedPreferenceChangeListener {

    SensorThread sensor = null;
    CommunicationThread communication = null;

    private int buttonsPressed = 0;

    //ColorStateList csl_normal = null;

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

        {   //Sweeps switch

            Switch sweepsSwitch = (Switch) findViewById(R.id.switchMoveSweeps);
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext());
            boolean b_use_sweeps = sp.getBoolean("use_sweeps", false);
            sweepsSwitch.setOnCheckedChangeListener(switchListener);
            sweepsSwitch.setChecked(b_use_sweeps);
        }

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


    private void updateButtonColors()
    {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext());
        boolean bSweeps = sp.getBoolean("use_sweeps",false);
        boolean bToggleMode = sp.getBoolean("hold_or_toggle_switch",false);

        int [] colors =  { 0xffd8d8d8, 0xffFFB7C5, 0xffE098A6 };
        for(int buttonId : button2bit.keySet())
        {
            int c=0;
            if( 0 != (buttonsPressed & (1<<button2bit.get(buttonId)))) c++;
            if(bSweeps && (
                    buttonId == R.id.buttonEngageLHand
                        || buttonId == R.id.buttonEngageRHand
                        || buttonId == R.id.buttonEngageLFoot
                        || buttonId == R.id.buttonEngageRFoot
                    )) c++;

            ((Button)findViewById(buttonId)).setBackgroundTintList(ColorStateList.valueOf( colors[c] ));
        }

    }

    private Switch.OnCheckedChangeListener switchListener = new Switch.OnCheckedChangeListener () {
        @Override
        public void onCheckedChanged(CompoundButton v, boolean isChecked) {
            Switch sw = (Switch)v;
            if( sw.getId() == R.id.switchMoveSweeps )
            {
                {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext());
                    sp.edit().putBoolean("use_sweeps",isChecked).apply();
                }

                /////////////////////////////////
                if(isChecked) buttonsPressed|=1<<30;
                else buttonsPressed&=~(1<<30);
                sendKeyboardState();

                //////////////////////

                updateButtonColors();
                /*
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
                */
            }
        }
    };

    TreeMap<Integer, Integer> button2bit = new TreeMap<Integer, Integer>() {{
        put(R.id.buttonEngageHead, 0);
        put(R.id.buttonEngageNeck, 1);

        put(R.id.buttonEngageLHand, 8);
        put(R.id.buttonEngageRHand, 9);
        put(R.id.buttonEngageLElbow, 10);
        put(R.id.buttonEngageRElbow, 11);
        put(R.id.buttonEngageLArm, 12);
        put(R.id.buttonEngageRArm, 13);

        put(R.id.buttonEngageChest, 17);
        put(R.id.buttonEngageHip, 16);

        put(R.id.buttonEngageLThigh, 28);
        put(R.id.buttonEngageRThigh, 29);
        put(R.id.buttonEngageLKnee, 26);
        put(R.id.buttonEngageRKnee, 27);
        put(R.id.buttonEngageLFoot, 24);
        put(R.id.buttonEngageRFoot, 25);
    }};

    private View.OnTouchListener btnTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Button b = (Button)v;
            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN)
            {
                if( button2bit.containsKey(b.getId()) )
                {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext());
                    boolean bToggleMode = sp.getBoolean("hold_or_toggle_switch",false);

                    if( bToggleMode ) {
                        buttonsPressed ^= 1 << button2bit.get(b.getId());
                    }else {
                        buttonsPressed |= 1 << button2bit.get(b.getId());
                    }

                    updateButtonColors();
                    sendKeyboardState();
                }

                return true;
            }
            else
            if (action == MotionEvent.ACTION_UP) {
                if( button2bit.containsKey(b.getId()) )
                {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext());
                    boolean bToggleMode = sp.getBoolean("hold_or_toggle_switch",false);
                    if( bToggleMode ) {
                        // do nothing
                    }else {
                        buttonsPressed &= ~(1 << button2bit.get(b.getId()));
                    }

                    updateButtonColors();
                    sendKeyboardState();
                }

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
