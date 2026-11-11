package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.content.SharedPreferences;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

final class AppPreferences {
    static final String STARTUP_PAGE = "startup_page";
    static final String PAGE_CAMERA = "camera";
    static final String PAGE_JOINTS = "joints";
    static final int VAM_PORT = 62701;

    static final String VAM_HOST = "host";
    static final String LAST_SERVER = "last_server";
    static final String ALIGN_HORIZONS = "align_horizons";
    static final String CAMERA_RESOLUTION = "camera_resolution";
    static final String POSITION_SCALE = "scale";
    static final String SCALE_PRESETS = "scale_presets";
    private static final String DEFAULT_SCALE_PRESETS = "0.6, 1, 3, 10";
    static final float MIN_POSITION_SCALE = 0.02f;
    static final float MAX_POSITION_SCALE = 25f;

    private AppPreferences() { }

    static SharedPreferences connectionAndCamera(Context context) {
        // Keep the existing store so upgrades retain the host and position scale.
        return context.getSharedPreferences("phone_camera", Context.MODE_PRIVATE);
    }

    static float readPositionScale(Context context) {
        try {
            float value = Float.parseFloat(connectionAndCamera(context).getString(POSITION_SCALE, "1"));
            if (Float.isFinite(value) && value >= MIN_POSITION_SCALE) {
                return Math.min(value, MAX_POSITION_SCALE);
            }
        } catch (NumberFormatException invalid) {
            // A previous version accepted unrestricted text.
        }
        return 1f;
    }

    static float[] readScalePresets(Context context) {
        String saved = connectionAndCamera(context).getString(SCALE_PRESETS, DEFAULT_SCALE_PRESETS);
        try {
            return parseScalePresets(saved, true);
        } catch (IllegalArgumentException invalid) {
            return parseScalePresets(DEFAULT_SCALE_PRESETS);
        }
    }

    static float[] parseScalePresets(String text) {
        return parseScalePresets(text, false);
    }

    private static float[] parseScalePresets(String text, boolean restoring) {
        Set<Float> values = new LinkedHashSet<>();
        for (String token : text.trim().split("[,\\s]+")) {
            if (token.endsWith("x") || token.endsWith("X") || token.endsWith("×")) {
                token = token.substring(0, token.length() - 1);
            }
            float value = Float.parseFloat(token);
            // Preserve the rest of a saved list if a previous version allowed a larger preset.
            if (restoring && Float.isFinite(value)) value = Math.min(value, MAX_POSITION_SCALE);
            if (!validPositionScale(value)) throw new IllegalArgumentException("Scale out of range");
            values.add(value);
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Enter at least one scale");

        float[] presets = new float[values.size()];
        int index = 0;
        for (float value : values) presets[index++] = value;
        return presets;
    }

    static String scaleNumber(float value) {
        return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }

    static boolean validPositionScale(float value) {
        return value >= MIN_POSITION_SCALE && value <= MAX_POSITION_SCALE;
    }
}
