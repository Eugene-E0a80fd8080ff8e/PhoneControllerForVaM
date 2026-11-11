package local.movementtrackerforvam.movementtrackerforvam;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import static local.movementtrackerforvam.movementtrackerforvam.CameraOverlayButton.dp;

class CameraOverlayMenu extends LinearLayout {
    private final boolean showChevron;
    private final CameraOverlayButton toggle;
    private final boolean opensUpward;
    private final ScrollView viewport;
    private final LinearLayout actions;
    private boolean expanded;

    CameraOverlayMenu(Context context, int gravity, boolean showChevron) {
        this(context, gravity, showChevron, false);
    }

    CameraOverlayMenu(Context context, int gravity, boolean showChevron, boolean opensUpward) {
        super(context);
        this.opensUpward = opensUpward;
        setOrientation(VERTICAL);
        setGravity(gravity);
        this.showChevron = showChevron;

        toggle = new CameraOverlayButton(context);
        if (showChevron) toggle.setTextSize(22);
        toggle.setOnClickListener(v -> setExpanded(!expanded));
        addView(toggle, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(context, 48)));
        updateToggle();

        viewport = new ScrollView(context);
        viewport.setVisibility(GONE);
        addView(viewport, opensUpward ? 0 : 1, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        actions = new LinearLayout(context);
        actions.setOrientation(VERTICAL);
        viewport.addView(actions, new ScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    Button addAction(CharSequence label, Runnable action) {
        Button button = new CameraOverlayButton(getContext());
        button.setText(label);
        actions.addView(button, new LayoutParams(LayoutParams.MATCH_PARENT, dp(getContext(), 48)));
        button.setOnClickListener(v -> {
            if (!collapse()) return;
            action.run();
        });
        return button;
    }

    void setToggleLabel(CharSequence text, CharSequence description) {
        if (!TextUtils.equals(toggle.getText(), text)) toggle.setText(text);
        if (!TextUtils.equals(toggle.getContentDescription(), description)) toggle.setContentDescription(description);
    }

    Button actionWithTag(Object tag) {
        return actions.findViewWithTag(tag);
    }

    void showSelection(Button selected) {
        toggle.showSelected(true);
        for (int i = 0; i < actions.getChildCount(); i++) {
            CameraOverlayButton action = (CameraOverlayButton) actions.getChildAt(i);
            action.showSelected(action == selected);
        }
    }

    void setToggleEnabled(boolean enabled) {
        if (!enabled) collapse();
        toggle.setEnabled(enabled);
    }

    void clearActions() {
        expanded = false;
        actions.animate().cancel();
        viewport.setVisibility(GONE);
        viewport.scrollTo(0, 0);
        actions.removeAllViews();
        updateToggle();
    }

    boolean collapse() {
        if (!expanded) return false;
        setExpanded(false);
        return true;
    }

    private void setExpanded(boolean open) {
        expanded = open;
        updateToggle();
        actions.animate().cancel();
        float slide = dp(getContext(), 48) * (opensUpward ? 1 : -1);
        if (open) {
            if (viewport.getVisibility() == GONE) {
                actions.setTranslationY(slide);
                actions.setAlpha(0);
                viewport.setVisibility(VISIBLE);
            }
            viewport.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            actions.animate().translationY(0).alpha(1).setDuration(180).start();
        } else {
            viewport.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            actions.animate().translationY(slide).alpha(0).setDuration(180)
                    .withEndAction(() -> {
                        if (!expanded) viewport.setVisibility(GONE);
                    }).start();
        }
    }

    private void updateToggle() {
        if (showChevron) {
            setToggleLabel(expanded ? "▴" : "▾", getContext().getString(expanded
                    ? R.string.camera_actions_close : R.string.camera_actions_open));
        }
    }
}
