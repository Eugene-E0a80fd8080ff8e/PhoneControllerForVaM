package local.movementtrackerforvam.movementtrackerforvam;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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
        button = new Button(context);
        button.setAllCaps(false);
        addView(button, new LayoutParams(-1, -2));
        explanation = new TextView(context);
        explanation.setPadding(12, 0, 12, 8);
        addView(explanation, new LayoutParams(-1, -2));
        button.setOnClickListener(v -> choose());
        render();
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
        if (chooser != null) chooser.dismiss();
        TargetConnection.release();
    }

    private void render() {
        TargetConnection.State state = TargetConnection.state;
        boolean connected = state != null && state.fresh();
        String label = "Target unavailable";
        String message = TargetConnection.notice;
        if (connected) {
            label = state.installation + " · " + state.name;
            if (!state.type.isEmpty()) label += " (" + state.type + ")";
            if (state.selectable) label += " ▾";
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
        button.setText(label);
        button.setEnabled(connected && state.selectable && !TargetConnection.pending());
        explanation.setText(message);
        explanation.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }

    private void choose() {
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
            TargetConnection.select(atom.id);
            dialog.dismiss();
            render();
            changed.run();
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
