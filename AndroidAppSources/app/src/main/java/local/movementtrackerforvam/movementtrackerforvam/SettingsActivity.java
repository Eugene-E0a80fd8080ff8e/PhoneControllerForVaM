package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.support.v7.app.ActionBar;
import android.view.MenuItem;
import android.widget.Toast;

public class SettingsActivity extends AppCompatPreferenceActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref_settings);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        ListPreference startup = (ListPreference) findPreference(AppPreferences.STARTUP_PAGE);
        startup.setSummary("%s");
        ListPreference jointRate = (ListPreference) findPreference("network_freq");
        jointRate.setSummary("%s");
        findPreference("vam_port").setSummary(Integer.toString(AppPreferences.VAM_PORT));
        findPreference("azimuth_setup").setIntent(new Intent(this, AzimuthSetup.class));

        EditTextPreference radius = (EditTextPreference) findPreference(AppPreferences.GRAB_RADIUS);
        showGrabRadius(radius, AppPreferences.readGrabRadius(radius.getSharedPreferences()));
        radius.setOnPreferenceChangeListener((preference, value) -> {
            try {
                showGrabRadius(radius, AppPreferences.parseGrabRadius(value.toString()));
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(this, R.string.pref_camera_grab_radius_invalid, Toast.LENGTH_SHORT).show();
            }
            return false; // The normalized value is saved by setText.
        });

        SharedPreferences preferences = AppPreferences.connectionAndCamera(this);
        EditTextPreference host = (EditTextPreference) findPreference(AppPreferences.VAM_HOST);
        host.setText(preferences.getString(AppPreferences.VAM_HOST, "").trim());
        updateHostSummary(host);
        host.setOnPreferenceChangeListener((preference, value) -> {
            String address = value.toString().trim();
            if (!address.equals(host.getText())) {
                preferences.edit().putString(AppPreferences.VAM_HOST, address)
                        .remove(AppPreferences.LAST_SERVER).apply();
            }
            host.setText(address);
            updateHostSummary(host);
            return false; // The trimmed value is already stored.
        });

        EditTextPreference presets = (EditTextPreference) findPreference(AppPreferences.SCALE_PRESETS);
        showScalePresets(presets, AppPreferences.readScalePresets(this));
        presets.setOnPreferenceChangeListener((preference, value) -> {
            try {
                float[] parsed = AppPreferences.parseScalePresets(value.toString());
                showScalePresets(presets, parsed);
                preferences.edit().putString(AppPreferences.SCALE_PRESETS, presets.getText()).apply();
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(this, R.string.pref_scale_presets_invalid, Toast.LENGTH_SHORT).show();
            }
            return false; // The validated list is saved in the camera preferences.
        });
    }

    private void showGrabRadius(EditTextPreference preference, float percent) {
        String value = AppPreferences.scaleNumber(percent);
        preference.setText(value);
        preference.setSummary(getString(R.string.pref_camera_grab_radius_value, value));
    }

    private void updateHostSummary(EditTextPreference host) {
        host.setSummary(host.getText().isEmpty()
                ? getString(R.string.pref_host_automatic) : host.getText());
    }

    private void showScalePresets(EditTextPreference preference, float[] scales) {
        StringBuilder text = new StringBuilder();
        StringBuilder summary = new StringBuilder();
        for (float scale : scales) {
            if (text.length() > 0) {
                text.append(", ");
                summary.append(", ");
            }
            String number = AppPreferences.scaleNumber(scale);
            text.append(number);
            summary.append(number).append('x');
        }
        preference.setText(text.toString());
        preference.setSummary(summary.toString());
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onMenuItemSelected(featureId, item);
    }
}
