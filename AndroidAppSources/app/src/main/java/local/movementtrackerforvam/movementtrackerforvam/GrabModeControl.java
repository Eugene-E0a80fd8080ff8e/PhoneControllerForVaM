package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;

import java.util.function.IntConsumer;

final class GrabModeControl extends CameraOverlayMenu {
    static final int POSITION = 2, ROTATION = 4, BOTH = POSITION | ROTATION, POS_RELAX = POSITION | 8, HEAD_ONLY = BOTH | 16;

    private final Button position, rotation, both, relax, headOnly;

    GrabModeControl(Context context, IntConsumer onModeSelected) {
        super(context, Gravity.END, false, true);
        position = addAction(context.getString(R.string.camera_grab_pos), () -> onModeSelected.accept(POSITION));
        rotation = addAction(context.getString(R.string.camera_grab_rot), () -> onModeSelected.accept(ROTATION));
        both = addAction(context.getString(R.string.camera_grab_pos_rot), () -> onModeSelected.accept(BOTH));
        relax = addAction(context.getString(R.string.camera_grab_pos_relax),
                () -> onModeSelected.accept(POS_RELAX));
        headOnly = addAction(context.getString(R.string.camera_grab_head_only),
                () -> onModeSelected.accept(HEAD_ONLY));
        showMode(POS_RELAX, true, true, true);
    }

    void showMode(int mode, boolean supported, boolean supportsRotationOff, boolean supportsHeadOnly) {
        relax.setVisibility(supportsRotationOff ? VISIBLE : GONE);
        headOnly.setVisibility(supportsHeadOnly ? VISIBLE : GONE);
        Button current;
        switch (mode) {
            case POSITION: current = position; break;
            case ROTATION: current = rotation; break;
            case POS_RELAX: current = relax; break;
            case HEAD_ONLY: current = headOnly; break;
            default: current = both;
        }
        CharSequence label = current.getText();
        setToggleLabel(label, getContext().getString(R.string.camera_grab_mode_label, label));
        showSelection(current);
        setToggleEnabled(supported);
    }
}
