package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.function.Consumer;

final class PositionScaleControl extends LinearLayout {
    private final Button current;
    private final FrameLayout presetViewport;
    private final HorizontalScrollView presetScroll;
    private final LinearLayout presets;
    private final Consumer<Float> onScaleSelected;
    private boolean expanded;

    PositionScaleControl(Context context, Consumer<Float> onScaleSelected) {
        super(context);
        this.onScaleSelected = onScaleSelected;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        current = createButton();
        current.setOnClickListener(v -> setExpanded(!expanded));
        addView(current, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)));

        presetViewport = new FrameLayout(context);
        presetViewport.setVisibility(GONE);
        addView(presetViewport, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)));

        presetScroll = new HorizontalScrollView(context);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetViewport.addView(presetScroll, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        presets = new LinearLayout(context);
        presets.setOrientation(HORIZONTAL);
        presetScroll.addView(presets);
    }

    void setScales(float scale, float[] available) {
        presetScroll.animate().cancel();
        expanded = false;
        presetViewport.setVisibility(GONE);
        setCurrentScale(scale);
        presets.removeAllViews();
        presetScroll.scrollTo(0, 0);

        for (float preset : available) {
            Button button = createButton();
            button.setText(AppPreferences.scaleNumber(preset) + "x");
            button.setOnClickListener(v -> {
                if (!expanded) return;
                onScaleSelected.accept(preset);
                setCurrentScale(preset);
                setExpanded(false);
            });
            LayoutParams layout = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(48));
            layout.setMarginStart(dp(4));
            presets.addView(button, layout);
        }
    }

    boolean collapse() {
        if (!expanded) return false;
        setExpanded(false);
        return true;
    }

    private void setExpanded(boolean open) {
        expanded = open;
        presetScroll.animate().cancel();
        float slide = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? dp(48) : -dp(48);
        if (open) {
            if (presetViewport.getVisibility() == GONE) {
                presetScroll.setTranslationX(slide);
                presetScroll.setAlpha(0);
                presetViewport.setVisibility(VISIBLE);
            }
            presetViewport.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            presetScroll.animate().translationX(0).alpha(1).setDuration(180).start();
        } else {
            presetViewport.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            presetScroll.animate().translationX(slide).alpha(0).setDuration(180)
                    .withEndAction(() -> {
                        if (!expanded) presetViewport.setVisibility(GONE);
                    }).start();
        }
    }

    private void setCurrentScale(float scale) {
        String label = AppPreferences.scaleNumber(scale) + "x";
        current.setText(label);
        current.setContentDescription(getContext().getString(R.string.camera_scale_accessibility, label));
    }

    private Button createButton() {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);

        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0xDF17212B);
        chip.setCornerRadius(dp(7));
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), chip, null);
        button.setBackground(new InsetDrawable(ripple, 0, dp(8), 0, dp(8)));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
