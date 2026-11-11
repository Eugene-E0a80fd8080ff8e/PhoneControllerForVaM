package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;

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
        Button selected = null;
        for (float preset : available) {
            Button button = addAction(AppPreferences.scaleNumber(preset) + "x", () -> {
                onScaleSelected.accept(preset);
                setCurrentScale(preset);
                showSelection(actionWithTag(preset));
            });
            button.setTag(preset);
            if (preset == scale) selected = button;
        }
        showSelection(selected);
    }

    private void setCurrentScale(float scale) {
        String label = AppPreferences.scaleNumber(scale) + "x";
        setToggleLabel(label, getContext().getString(R.string.camera_scale_accessibility, label));
    }
}
