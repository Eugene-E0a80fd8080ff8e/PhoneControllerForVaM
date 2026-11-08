package local.movementtrackerforvam.movementtrackerforvam;
import android.os.Bundle;
import android.preference.*;
public class CameraSettingsActivity extends PreferenceActivity {
 @Override public void onCreate(Bundle b) { super.onCreate(b); getPreferenceManager().setSharedPreferencesName("phone_camera");
  PreferenceScreen screen=getPreferenceManager().createPreferenceScreen(this);
  EditTextPreference host=new EditTextPreference(this);host.setKey("host");host.setTitle("VaM host (blank for discovery)");host.setSummary("Ubuntu hotspot address is usually 192.168.12.1");screen.addPreference(host);
  EditTextPreference scale=new EditTextPreference(this);scale.setKey("scale");scale.setTitle("Position scale");scale.setDefaultValue("1");screen.addPreference(scale);
  setPreferenceScreen(screen);
 }
}
