package local.movementtrackerforvam.movementtrackerforvam;

import android.Manifest;
import android.content.pm.PackageManager;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
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
    private PositionTracker positionTracker;
    private boolean positionRequested;
    private boolean installRequested;
    private boolean activityResumed;
    private static final int CAMERA_PERMISSION = 41;

    //ColorStateList csl_normal = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Older AppCompat versions can lay content underneath the action bar on
        // edge-to-edge Android. Only add space when the two actually overlap.
        View content = findViewById(R.id.controllerScroll);
        View actionBar = findViewById(android.support.v7.appcompat.R.id.action_bar_container);
        if (actionBar != null) {
            content.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                int[] contentPosition = new int[2];
                int[] barPosition = new int[2];
                content.getLocationInWindow(contentPosition);
                actionBar.getLocationInWindow(barPosition);
                int top = Math.max(0, barPosition[1] + actionBar.getHeight() - contentPosition[1]);
                if (content.getPaddingTop() != top) {
                    content.setPadding(content.getPaddingLeft(), top,
                            content.getPaddingRight(), content.getPaddingBottom());
                }
            });
        }

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

        Switch positionSwitch = findViewById(R.id.switchPositionTracking);
        positionSwitch.setOnCheckedChangeListener((button, enabled) -> {
            positionRequested = enabled;
            if (enabled) {
                Toast.makeText(this, "Position tracking requires the updated VaM plugin", Toast.LENGTH_LONG).show();
                startPositionTracking();
            }
            else stopPositionTracking();
            updateButtonColors();
        });

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


    private void updateButtonColors() {
        for (int buttonId : button2bit.keySet()) {
            int color = (buttonsPressed & (1 << button2bit.get(buttonId))) != 0 ? 0xffFFB7C5 : 0xffd8d8d8;
            ((Button) findViewById(buttonId)).setBackgroundTintList(ColorStateList.valueOf(color));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (positionRequested) startPositionTracking();
    }

    @Override protected void onPause() {
        activityResumed = false;
        stopPositionTracking();
        super.onPause();
    }

    private void positionStatus(String message) {
        runOnUiThread(() -> ((Switch) findViewById(R.id.switchPositionTracking)).setText(message));
    }

    private void stopPositionTracking() {
        PositionTracker.sample = null;
        if (positionTracker != null) {
            positionTracker.stop();
            positionTracker = null;
        }
        positionStatus("Position tracking");
    }

    private void startPositionTracking() {
        if (!activityResumed || !positionRequested || positionTracker != null) return;
        positionStatus("Position: starting");
        ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
        if (availability.isTransient()) {
            new android.os.Handler(getMainLooper()).postDelayed(this::startPositionTracking, 300);
            return;
        }
        if (!availability.isSupported()) {
            positionStatus("Position: device unsupported");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        Session session = null;
        try {
            if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true;
                return;
            }
            session = new Session(this);
            Config config = session.getConfig();
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            session.configure(config);
            session.resume();
            positionTracker = new PositionTracker(session, this::positionStatus);
            positionTracker.start();
        } catch (Exception e) {
            if (session != null) session.close();
            positionStatus("Position unavailable: " + e.getClass().getSimpleName());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startPositionTracking();
            else {
                ((Switch) findViewById(R.id.switchPositionTracking)).setChecked(false);
                Toast.makeText(this, "Camera permission is required for position tracking", Toast.LENGTH_LONG).show();
            }
        }
    }

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
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
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
