package local.movementtrackerforvam.movementtrackerforvam;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ListPopupWindow;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Target selector and capability explanation, shared by the two screens. */
public final class TargetBar extends LinearLayout {
    private final Button button;
    private final TextView explanation;
    private final Handler handler = new Handler();
    private final boolean controller;
    private final Runnable changed;
    private boolean active;
    private Runnable frameCurrent;
    private ListPopupWindow peoplePicker;
    private List<TargetConnection.AtomChoice> people = new ArrayList<>();
    private int pickerInstance;
    private AlertDialog chooser;
    private Runnable updateChoices;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            render();
            changed.run();
            handler.postDelayed(this, 200);
        }
    };

    public TargetBar(Context context, boolean controller, Runnable changed) {
        super(context);
        this.controller = controller;
        this.changed = changed;
        setOrientation(VERTICAL);
        button = controller ? new Button(context) : new CameraOverlayButton(context);
        button.setAllCaps(false);
        explanation = new TextView(context);
        if (controller) {
            addView(button, new LayoutParams(-1, -2));
            explanation.setPadding(12, 0, 12, 8);
            addView(explanation, new LayoutParams(-1, -2));
        } else {
            setGravity(Gravity.START);
            button.setSingleLine();
            button.setEllipsize(TextUtils.TruncateAt.END);
            explanation.setTextColor(Color.WHITE);
            explanation.setBackground(CameraOverlayButton.background(context, 0xDF17212B, false));
            explanation.setPadding(dp(8), dp(12), dp(8), dp(12));
            addView(explanation, new LayoutParams(-2, -2));
            addView(button, new LayoutParams(-2, dp(48)));
        }
        button.setOnClickListener(v -> {
            if (controller) choose();
            else openCameraTargets();
        });
        render();
    }

    void setFrameCurrentAction(Runnable action) {
        frameCurrent = action;
    }

    public void start() {
        if (active) return;
        active = true;
        TargetConnection.acquire();
        handler.post(refresh);
    }

    public void stop() {
        if (!active) return;
        active = false;
        handler.removeCallbacks(refresh);
        dismissPickers();
        TargetConnection.release();
    }

    private void render() {
        TargetConnection.State state = TargetConnection.state;
        boolean connected = state != null && state.fresh();
        String label = "Target unavailable";
        String message = TargetConnection.notice;
        if (connected) {
            label = state.name;
            if (controller) {
                label = state.installation + " · " + label;
                if (!state.type.isEmpty()) label += " (" + state.type + ")";
            }
            if (state.selectable) label += controller ? " ▾" : " ▴";
            if (state.loading) {
                message = "Scene is loading";
            } else if (state.target == 0 && message.isEmpty()) {
                message = state.framing.isEmpty() ? "Choose a target" : state.framing;
            } else if (controller && state.target != 0 && !state.control) {
                String reason = state.type.equals("Person") ? "Person controls are not ready"
                        : "Joint control requires a Person. Current target is “" + state.name + "” (" + state.type + ").";
                message = message.isEmpty() ? reason : reason + "\n" + message;
            } else if (message.isEmpty()) {
                message = state.framing;
            }
        } else if (message.isEmpty()) {
            message = "Disconnected — waiting for target information";
        }
        if (!TextUtils.equals(button.getText(), label)) button.setText(label);
        boolean canSelect = connected && state.selectable && !TargetConnection.pending();
        boolean canFrame = !controller && connected && state.target != 0 && !state.loading
                && !TargetConnection.pending();
        button.setEnabled(canSelect || canFrame);
        if (!TextUtils.equals(explanation.getText(), message)) explanation.setText(message);
        explanation.setVisibility(message.isEmpty() ? GONE : VISIBLE);

        if (peoplePicker != null || chooser != null) {
            if (!canSelect || pickerInstance != state.instance) dismissPickers();
            else if (peoplePicker != null) updatePeoplePicker();
        }
    }

    private void openCameraTargets() {
        TargetConnection.State state = TargetConnection.state;
        if (state == null || !state.fresh() || state.loading || TargetConnection.pending()) return;
        if (!state.selectable) {
            if (state.target != 0 && frameCurrent != null) frameCurrent.run();
            return;
        }

        pickerInstance = state.instance;
        peoplePicker = new ListPopupWindow(getContext());
        peoplePicker.setAnchorView(button);
        peoplePicker.setDropDownGravity(Gravity.START);
        peoplePicker.setModal(true);
        peoplePicker.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
        peoplePicker.setWidth(Math.min(dp(280), getRootView().getWidth() - dp(16)));
        peoplePicker.setOnItemClickListener((parent, view, position, id) -> {
            TargetConnection.AtomChoice selected = position < people.size() ? people.get(position) : null;
            peoplePicker.dismiss();
            if (selected == null) choose();
            else selectAtom(selected);
        });
        peoplePicker.setOnDismissListener(() -> peoplePicker = null);
        updatePeoplePicker();
        peoplePicker.show();
    }

    private void updatePeoplePicker() {
        List<TargetConnection.AtomChoice> current = new ArrayList<>();
        for (TargetConnection.AtomChoice atom : TargetConnection.choices()) {
            if ("Person".equals(atom.type)) current.add(atom);
        }
        if (peoplePicker.getListView() != null && people.equals(current)) return;
        people = current;
        List<String> labels = new ArrayList<>();
        for (TargetConnection.AtomChoice atom : people) labels.add(atom.name);
        labels.add(getContext().getString(R.string.camera_target_show_all));
        peoplePicker.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, labels));
        if (peoplePicker.isShowing()) peoplePicker.show();
    }

    private void dismissPickers() {
        if (peoplePicker != null) peoplePicker.dismiss();
        if (chooser != null) chooser.dismiss();
    }

    private void selectAtom(TargetConnection.AtomChoice atom) {
        TargetConnection.State state = TargetConnection.state;
        if (state == null || !state.fresh() || state.instance != pickerInstance || TargetConnection.pending()) return;
        if (!controller && state.target == atom.id && frameCurrent != null) frameCurrent.run();
        else TargetConnection.select(atom.id);
        render();
        changed.run();
    }

    private int dp(int value) {
        return CameraOverlayButton.dp(getContext(), value);
    }

    private void choose() {
        TargetConnection.State state = TargetConnection.state;
        if (state == null || !state.fresh() || !state.selectable || TargetConnection.pending()) return;
        pickerInstance = state.instance;
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(VERTICAL);
        EditText search = new EditText(getContext());
        search.setSingleLine(true);
        search.setHint("Search atoms");
        panel.addView(search);
        ListView list = new ListView(getContext());
        int height = (int) (360 * getResources().getDisplayMetrics().density);
        panel.addView(list, new LayoutParams(-1, height));
        list.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1,
                new ArrayList<>(TargetConnection.choices())));
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("Choose target")
                .setView(panel)
                .setNegativeButton("Cancel", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            TargetConnection.AtomChoice atom = (TargetConnection.AtomChoice) parent.getItemAtPosition(position);
            dialog.dismiss();
            selectAtom(atom);
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void afterTextChanged(Editable text) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                ((ArrayAdapter<?>) list.getAdapter()).getFilter().filter(text);
            }
        });
        chooser = dialog;
        updateChoices = new Runnable() {
            private List<TargetConnection.AtomChoice> previous;
            public void run() {
                if (chooser != dialog) return;
                List<TargetConnection.AtomChoice> current = TargetConnection.choices();
                if (current != previous) {
                    previous = current;
                    ArrayAdapter<TargetConnection.AtomChoice> updated = new ArrayAdapter<>(
                            getContext(), android.R.layout.simple_list_item_1, new ArrayList<>(current));
                    list.setAdapter(updated);
                    updated.getFilter().filter(search.getText().toString());
                }
                handler.postDelayed(this, 300);
            }
        };
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(updateChoices);
            chooser = null;
        });
        dialog.show();
        handler.post(updateChoices);
    }
}
