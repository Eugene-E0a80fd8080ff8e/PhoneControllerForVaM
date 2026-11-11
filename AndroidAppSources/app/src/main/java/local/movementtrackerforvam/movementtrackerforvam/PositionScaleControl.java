package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.view.Gravity;

import java.util.function.Consumer;

final class PositionScaleControl extends CameraOverlayMenu {
    private final Consumer<Float> onScaleSelected;

    PositionScaleControl(Context context, Consumer<Float> onScaleSelected) {
        super(context, Gravity.START, false);
        this.onScaleSelected = onScaleSelected;
    }

    void setScales(float scale, float[] available) {
        clearActions();
        setCurrentScale(scale);
        for (float preset : available) {
            addAction(AppPreferences.scaleNumber(preset) + "x", () -> {
                onScaleSelected.accept(preset);
                setCurrentScale(preset);
            });
        }
    }

    private void setCurrentScale(float scale) {
        String label = AppPreferences.scaleNumber(scale) + "x";
        setToggleLabel(label, getContext().getString(R.string.camera_scale_accessibility, label));
    }
}
