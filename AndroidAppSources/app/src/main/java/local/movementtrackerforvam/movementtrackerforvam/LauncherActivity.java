package local.movementtrackerforvam.movementtrackerforvam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;

public final class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String page = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(AppPreferences.STARTUP_PAGE, AppPreferences.PAGE_CAMERA);
        Class<?> destination = AppPreferences.PAGE_JOINTS.equals(page)
                ? MainActivity.class : CameraActivity.class;
        startActivity(new Intent(this, destination));
        finish();
    }
}
