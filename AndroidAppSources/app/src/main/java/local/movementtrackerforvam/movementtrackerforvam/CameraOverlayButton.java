package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;

final class CameraOverlayButton extends Button {
    private static final int DEFAULT_COLOR = 0xDF17212B;
    private static final int SELECTED_COLOR = 0xFF1556A0;
    CameraOverlayButton(Context context) {
        super(context);
        setAllCaps(false);
        setMinWidth(dp(context, 48));
        setMinimumWidth(dp(context, 48));
        setGravity(Gravity.CENTER);
        setTextColor(Color.WHITE);
        setTextSize(14);
        setElevation(0);
        setStateListAnimator(null);
        setBackgroundTintList(null);

        setBackground(background(context, DEFAULT_COLOR, true));
        setPadding(dp(context, 8), 0, dp(context, 8), 0);
    }

    void showSelected(boolean selected) {
        if (isSelected() == selected) return;
        setSelected(selected);
        setBackground(background(getContext(), selected ? SELECTED_COLOR : DEFAULT_COLOR, true));
        int padding = dp(getContext(), 8);
        setPadding(padding, 0, padding, 0);
    }

    static InsetDrawable background(Context context, int color, boolean ripple) {
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(color);
        chip.setCornerRadius(dp(context, 7));
        return new InsetDrawable(
                ripple ? new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), chip, null) : chip,
                0, dp(context, 8), 0, dp(context, 8));
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
