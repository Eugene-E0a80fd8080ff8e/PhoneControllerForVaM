package local.movementtrackerforvam.movementtrackerforvam;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.concurrent.ConcurrentLinkedQueue;

public class MyApplication extends Application {
    private static volatile Context context =  null;

    public static ConcurrentLinkedQueue<SensorDatum> sensorDataPipe = new ConcurrentLinkedQueue<>();
    public static volatile SensorDatum keysSensorData = null;

    public void onCreate() {
        super.onCreate();
        MyApplication.context = getApplicationContext();

        //cleanup for debug purposes
        /*
        PreferenceManager.getDefaultSharedPreferences(MyApplication.getAppContext())
                .edit()
                .clear()
                .apply();
        */
    }

    public static Context getAppContext() {

        return MyApplication.context;
    }
}
