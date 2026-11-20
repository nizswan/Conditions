package com.example.conditions;

import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;
import java.util.ArrayList;

public class ContextWebFragment extends LiveConditionsFragment {
    static final String[] AXES = {"Date", "Time - Daily", "Battery (%)", "Temperature (°F)",
            "History (minutes)", "Wi-Fi", "Bluetooth", "Weather", "Space X (longitude)", "Space Y (latitude)", "Time - Continuous"};
    private static final int[] AXIS_ORDER = {0, 1, 10, 2, 3, 4, 5, 6, 7, 8, 9};
    static class Item {
        String id, title;
        JSONObject context;
        Object value(int axis) {
            JSONObject nested;
            switch (axis) {
                case 0:
                    Double savedAt = number(context, "submitted_at");
                    if (savedAt == null) return null;
                    JSONObject savedTime = context.optJSONObject("time");
                    String zone = context.optString("submitted_timezone", savedTime == null
                            ? java.util.TimeZone.getDefault().getID() : savedTime.optString("timezone", "UTC"));
                    java.util.Calendar localDate = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone));
                    localDate.setTimeInMillis(savedAt.longValue());
                    // Plot calendar days independently of the time of day or daylight saving changes.
                    java.util.Calendar date = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                    date.clear();
                    date.set(localDate.get(java.util.Calendar.YEAR), localDate.get(java.util.Calendar.MONTH),
                            localDate.get(java.util.Calendar.DAY_OF_MONTH));
                    return (double) date.getTimeInMillis();
                case 1:
                    nested = context.optJSONObject("time");
                    Double timestamp = number(nested, "timestamp");
                    if (timestamp == null) return null;
                    java.util.Calendar time = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(nested.optString("timezone", "UTC")));
                    time.setTimeInMillis(timestamp.longValue());
                    return (double) (time.get(java.util.Calendar.HOUR_OF_DAY) * 60 + time.get(java.util.Calendar.MINUTE));
                case 2: return number(context, "battery");
                case 3: return number(context.optJSONObject("environment"), "temperature_f");
                case 4: return number(context, "history");
                case 5: return context.isNull("wifi") ? null : context.optString("wifi").isEmpty() ? "None" : context.optString("wifi");
                case 6:
                    nested = context.optJSONObject("bluetooth");
                    if (nested == null || !nested.optBoolean("known")) return null;
                    org.json.JSONArray devices = nested.optJSONArray("devices");
                    if (devices == null) return null;
                    java.util.TreeSet<String> names = new java.util.TreeSet<>();
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject device = devices.optJSONObject(i);
                        if (device != null) names.add(device.optString("name", device.optString("id", "Device")));
                    }
                    return names.isEmpty() ? "None" : android.text.TextUtils.join(" + ", names);
                case 7:
                    nested = context.optJSONObject("environment");
                    return nested == null || nested.isNull("weather") ? null : nested.optString("weather");
                case 8: return number(context.optJSONObject("location"), "longitude");
                case 9: return number(context.optJSONObject("location"), "latitude");
                case 10: return number(context, "submitted_at");
                default: return null;
            }
        }
        private Double number(JSONObject object, String key) {
            double value = object == null ? Double.NaN : object.optDouble(key, Double.NaN);
            return Double.isFinite(value) ? value : null;
        }
    }
    public static class WebState extends androidx.lifecycle.ViewModel {
        final androidx.lifecycle.SavedStateHandle state;
        public WebState(androidx.lifecycle.SavedStateHandle state) { this.state = state; }
    }
    private WebState webState;
    private long libraryId;
    private boolean neighbors;
    private long shuffleSeed = new java.util.Random().nextLong();
    private int neighborCount = 1, displayLimit = 20;
    private double[] metricWeights = new double[AXES.length];
    private final java.util.HashSet<String> hiddenItems = new java.util.HashSet<>();
    private String libraryTitle = "", rendered = "";
    private Spinner x, y, z, w;
    private LinearLayout body;
    private LibraryPage opened;
    private boolean choosingItem, showingFigure;
    private androidx.activity.OnBackPressedCallback back;
    private androidx.appcompat.app.AlertDialog chooser;
    private final ArrayList<Item> items = new ArrayList<>();

    public ContextWebFragment() { super(R.layout.fragment_context_web); }
    private String username() { return requireArguments().getString("username", ""); }
    @Override public void onViewCreated(View view, Bundle state) {
        super.onViewCreated(view, state);
        webState = new androidx.lifecycle.ViewModelProvider(requireActivity())
                .get("content_web_" + username(), WebState.class);
        if (state == null) state = webState.state.get("selection");
        body = view.findViewById(R.id.web_body); x = view.findViewById(R.id.web_x); y = view.findViewById(R.id.web_y);
        z = view.findViewById(R.id.web_z); w = view.findViewById(R.id.web_w);
        ArrayList<String> options = new ArrayList<>(); options.add("Select axis");
        for (int axis : AXIS_ORDER) options.add(AXES[axis]);
        for (Spinner selector : new Spinner[]{x, y, z, w})
            selector.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, options));
        if (state != null) {
            libraryId = state.getLong("library"); x.setSelection(state.getInt("x")); y.setSelection(state.getInt("y"));
            z.setSelection(state.getInt("z")); w.setSelection(state.getInt("w"));
            showingFigure = state.getBoolean("figure");
            shuffleSeed = state.getLong("shuffle_seed", shuffleSeed);
            neighbors = state.getBoolean("neighbors"); neighborCount = state.getInt("k", 1); displayLimit = state.getInt("limit", 20);
            double[] weights = state.getDoubleArray("weights"); if (weights != null) metricWeights = weights;
            ArrayList<String> hidden = state.getStringArrayList("hidden"); if (hidden != null) hiddenItems.addAll(hidden);
        }
        back = new androidx.activity.OnBackPressedCallback(showingFigure) {
            @Override public void handleOnBackPressed() { showStep(false); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        view.findViewById(R.id.web_back).setOnClickListener(v -> showStep(false));
        view.findViewById(R.id.web_continue).setOnClickListener(v -> {
            if (unlocked() && !dimensions().isEmpty()) showStep(true);
            else readingsChanged();
        });
        AdapterView.OnItemSelectedListener axes = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { rendered = ""; readingsChanged(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        for (Spinner selector : new Spinner[]{x, y, z, w}) selector.setOnItemSelectedListener(axes);
        view.findViewById(R.id.select_library).setOnClickListener(v -> selectLibrary());
        CheckBox edges = view.findViewById(R.id.web_edges); edges.setChecked(neighbors);
        edges.setOnCheckedChangeListener((button, checked) -> {
            neighbors = checked;
            if (checked && !hasMetric()) for (int axis : dimensions()) metricWeights[axis] = 1;
            redraw();
        });
        view.findViewById(R.id.web_display).setOnClickListener(v -> selectDisplayedItems());
        view.findViewById(R.id.web_reshuffle).setOnClickListener(v -> { shuffleSeed++; redraw(); });
        view.findViewById(R.id.web_metric).setOnClickListener(v -> editMetric());
        view.findViewById(R.id.web_neighbor_count).setOnClickListener(v -> editNeighborCount());
        ((Button) view.findViewById(R.id.web_neighbor_count)).setText("Neighbors per item: " + neighborCount);
        showStep(showingFigure);
    }
    private void showStep(boolean figure) {
        showingFigure = figure; back.setEnabled(figure);
        for (int control : new int[]{R.id.web_z, R.id.web_w, R.id.web_z_label, R.id.web_w_label, R.id.web_neighbors, R.id.web_display, R.id.web_reshuffle})
            getView().findViewById(control).setVisibility(figure ? View.VISIBLE : View.GONE);
        View controls = getView().findViewById(R.id.web_controls);
        ((android.view.ViewGroup) controls.getParent()).removeView(controls);
        ((LinearLayout) getView().findViewById(figure ? R.id.web_figure_controls : R.id.web_setup_content)).addView(controls);
        getView().findViewById(R.id.web_continue).setVisibility(figure ? View.GONE : View.VISIBLE);
        getView().findViewById(R.id.web_setup).setVisibility(figure ? View.GONE : View.VISIBLE);
        getView().findViewById(R.id.web_figure).setVisibility(figure ? View.VISIBLE : View.GONE);
        getView().findViewById(R.id.web_back).setVisibility(figure ? View.VISIBLE : View.GONE);
        rendered = ""; readingsChanged();
    }
    private void selectLibrary() {
        ArrayList<Long> ids = new ArrayList<>(); ArrayList<String> titles = new ArrayList<>();
        try (LibraryStore store = new LibraryStore(requireContext()); Cursor rows = store.forUser(username())) {
            while (rows.moveToNext()) {
                ids.add(rows.getLong(0));
                titles.add(rows.getString(1) + (ConditionRule.evaluate(ConditionRule.read(rows.getString(2)), signals) == ConditionRule.State.TRUE ? "" : " (locked)"));
            }
        }
        if (ids.isEmpty()) { Toast.makeText(requireContext(), "No libraries", Toast.LENGTH_SHORT).show(); return; }
        choosingItem = false;
        chooser = new MaterialAlertDialogBuilder(requireContext()).setTitle("Select library")
                .setItems(titles.toArray(new String[0]), (d, which) -> { libraryId = ids.get(which); rendered = ""; readingsChanged(); }).show();
    }
    private boolean unlocked() {
        try (LibraryStore store = new LibraryStore(requireContext()); Cursor rows = store.forUser(username())) {
            while (rows.moveToNext()) if (rows.getLong(0) == libraryId) {
                libraryTitle = rows.getString(1);
                return ConditionRule.evaluate(ConditionRule.read(rows.getString(2)), signals) == ConditionRule.State.TRUE;
            }
        }
        libraryId = 0; libraryTitle = ""; return false;
    }
    @Override protected void readingsChanged() {
        if (getView() == null || body == null) return;
        boolean active = unlocked();
        body.setVisibility(View.VISIBLE);
        getView().findViewById(R.id.web_display).setEnabled(active);
        getView().findViewById(R.id.web_reshuffle).setEnabled(false);
        ((TextView) getView().findViewById(R.id.select_library)).setText(libraryId == 0 ? "Select library" : libraryTitle);
        for (Spinner selector : new Spinner[]{x, y, z, w}) selector.setEnabled(libraryId != 0);
        getView().findViewById(R.id.web_continue).setEnabled(active && !dimensions().isEmpty());
        ((TextView) getView().findViewById(R.id.web_status)).setText(libraryId != 0 && !active ? "This library is locked until its conditions are met." : "");
        ((TextView) getView().findViewById(R.id.web_library_title)).setText(libraryTitle);
        if (opened != null && opened.isShowing()) { if (active) opened.resumeContent(); else opened.dismiss(); }
        if (!active) {
            if (chooser != null && choosingItem) chooser.dismiss();
            items.clear(); body.removeAllViews(); rendered = "";
            label(libraryId == 0 ? "Select a library to explore its items." : "This library is locked until its conditions are met."); return;
        }
        if (!showingFigure) return;
        ArrayList<Item> savedItems = new ArrayList<>();
        ArrayList<Integer> dimensions = dimensions();
        StringBuilder signature = new StringBuilder(libraryId + ":" + dimensions.toString());
        try (LibraryStore store = new LibraryStore(requireContext()); Cursor rows = store.items(username(), libraryId)) {
            while (rows.moveToNext()) {
                String id = rows.getString(3);
                signature.append(id).append(rows.getString(0)).append(rows.getString(4));
                Item p = new Item(); p.id = id; p.title = rows.getString(0); p.context = ConditionRule.read(rows.getString(4)); savedItems.add(p);
            }
        }
        if (rendered.equals(signature.toString())) return;
        rendered = signature.toString(); items.clear(); items.addAll(savedItems); body.removeAllViews();
        if (items.isEmpty()) { label("No content saved in this library yet."); return; }
        if (dimensions.isEmpty()) { label("Select an axis below."); return; }
        int horizontal = dimensions.get(0), vertical = dimensions.size() > 1 ? dimensions.get(1) : -1;
        int color = dimensions.size() > 2 ? dimensions.get(2) : -1, size = dimensions.size() > 3 ? dimensions.get(3) : -1;
        ArrayList<Item> sampled = new ArrayList<>();
        for (Item item : items) if (!hiddenItems.contains(item.id)) sampled.add(item);
        int eligibleCount = sampled.size();
        getView().findViewById(R.id.web_reshuffle).setEnabled(displayLimit > 0 && eligibleCount > displayLimit);
        // Sampling happens before checking axes, so changing axes or weights never replaces an item.
        if (displayLimit > 0 && sampled.size() > displayLimit) {
            java.util.Collections.shuffle(sampled, new java.util.Random(shuffleSeed ^ libraryId));
            sampled = new ArrayList<>(sampled.subList(0, displayLimit));
        }
        ArrayList<Item> plotted = new ArrayList<>(), missing = new ArrayList<>();
        for (Item p : sampled) {
            boolean available = true;
            for (int dimension : dimensions) if (p.value(dimension) == null) available = false;
            if (available) plotted.add(p); else missing.add(p);
        }
        label("Showing " + plotted.size() + " of " + sampled.size() + " selected items (" + eligibleCount + " available)");
        if (!plotted.isEmpty()) {
            HorizontalScrollView scroll = new HorizontalScrollView(requireContext());
            // Axis ranges come from the whole library, including items missing the other axis.
            ContentWebPlot plot = new ContentWebPlot(requireContext(), items, plotted, horizontal, vertical, color, size, this::openGroup);
            if (neighbors && hasMetric()) {
                NeighborMetric metric = new NeighborMetric(items, metricWeights);
                plot.setEdges(metric.edges(plotted, neighborCount, shuffleSeed));
                int unavailable = 0;
                for (Item item : plotted) if (!metric.available(item)) unavailable++;
                if (unavailable > 0) label(unavailable + " displayed items have missing similarity readings and no edges.");
            }
            scroll.addView(plot, new android.view.ViewGroup.LayoutParams(plot.plotWidth, plot.plotHeight)); body.addView(scroll);
            plot.addLegends(body);
        } else label(sampled.isEmpty() ? "No items selected for display." : "No saved readings for these axes.");
        if (!missing.isEmpty()) {
            label("No saved reading (" + missing.size() + ")");
            for (Item p : missing) {
                Button button = new Button(requireContext()); button.setAllCaps(false); button.setText(p.title);
                button.setOnClickListener(v -> open(p)); body.addView(button);
            }
        }
    }
    private void redraw() { rendered = ""; readingsChanged(); }
    private boolean hasMetric() { for (double weight : metricWeights) if (weight > 0) return true; return false; }
    private LinearLayout optionsForm() {
        LinearLayout form = new LinearLayout(requireContext()); form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density); form.setPadding(padding, padding, padding, padding); return form;
    }
    private EditText numericInput(String hint, String value, boolean decimal) {
        EditText input = new EditText(requireContext()); input.setHint(hint); input.setSingleLine(true); input.setText(value);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0)); return input;
    }
    private void selectDisplayedItems() {
        if (!unlocked()) return;
        LinearLayout form = optionsForm();
        TextView caption = new TextView(requireContext()); caption.setText("Maximum plotted items (blank = all)."); form.addView(caption);
        EditText limit = new EditText(requireContext()); limit.setSingleLine(true); limit.setHint("20");
        limit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        limit.setText(displayLimit == 0 ? "" : "" + displayLimit); form.addView(limit);
        ArrayList<CheckBox> checks = new ArrayList<>(); ArrayList<Item> choices = new ArrayList<>(items);
        LinearLayout buttons = new LinearLayout(requireContext()); form.addView(buttons);
        for (boolean select : new boolean[]{true, false}) {
            Button button = new Button(requireContext()); button.setAllCaps(false); button.setText(select ? "All" : "None");
            button.setOnClickListener(v -> { for (CheckBox check : checks) check.setChecked(select); }); buttons.addView(button);
        }
        for (Item item : choices) {
            CheckBox check = new CheckBox(requireContext()); check.setText(item.title); check.setChecked(!hiddenItems.contains(item.id));
            checks.add(check); form.addView(check);
        }
        ScrollView scroll = new ScrollView(requireContext()); scroll.addView(form); choosingItem = true;
        chooser = new MaterialAlertDialogBuilder(requireContext()).setTitle("Items to display").setView(scroll)
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).show();
        chooser.getButton(-1).setOnClickListener(v -> {
            int maximum;
            try {
                String value = limit.getText().toString().trim();
                boolean all = value.isEmpty() || value.equalsIgnoreCase("all");
                maximum = all ? 0 : Integer.parseInt(value);
                if (!all && maximum < 1) throw new NumberFormatException();
            } catch (NumberFormatException error) { limit.setError("Enter a positive whole number, all, or leave blank."); return; }
            if (!unlocked()) { chooser.dismiss(); redraw(); return; }
            displayLimit = maximum;
            for (int i = 0; i < choices.size(); i++) if (checks.get(i).isChecked()) hiddenItems.remove(choices.get(i).id); else hiddenItems.add(choices.get(i).id);
            chooser.dismiss(); redraw();
        });
    }
    private void editNeighborCount() {
        EditText count = numericInput("Neighbors per item", "" + neighborCount, false); choosingItem = false;
        chooser = new MaterialAlertDialogBuilder(requireContext()).setTitle("Neighbors per item").setView(count)
                .setMessage("Each item chooses this many neighbors, up to the number available. Other items can also connect to it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).show();
        chooser.getButton(-1).setOnClickListener(v -> {
            try { int value = Integer.parseInt(count.getText().toString()); if (value < 1) throw new NumberFormatException(); neighborCount = value; }
            catch (NumberFormatException error) { count.setError("Enter a positive whole number."); return; }
            ((Button) getView().findViewById(R.id.web_neighbor_count)).setText("Neighbors per item: " + neighborCount);
            chooser.dismiss(); redraw();
        });
    }
    private void editMetric() {
        LinearLayout form = optionsForm(), factors = optionsForm();
        TextView explanation = new TextView(requireContext()); explanation.setText("Build the metric used to identify items' nearest neighbors."); form.addView(explanation);
        form.addView(factors); TextView summary = new TextView(requireContext()); form.addView(summary);
        ArrayList<Spinner> types = new ArrayList<>(); ArrayList<EditText> weights = new ArrayList<>();
        Runnable update = () -> {
            double total = 0, largest = 0; double[] values = new double[weights.size()];
            for (int i = 0; i < values.length; i++) {
                try { double value = Double.parseDouble(weights.get(i).getText().toString()); if (Double.isFinite(value) && value >= 0) values[i] = value; } catch (NumberFormatException ignored) {}
                int axis = AXIS_ORDER[types.get(i).getSelectedItemPosition()];
                if (axis >= 5 && axis <= 7) continue;
                largest = Math.max(largest, values[i]);
            }
            if (largest > 0) for (int i = 0; i < values.length; i++) {
                int axis = AXIS_ORDER[types.get(i).getSelectedItemPosition()];
                if (axis < 5 || axis > 7) total += values[i] / largest;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                int axis = AXIS_ORDER[types.get(i).getSelectedItemPosition()];
                text.append(types.get(i).getSelectedItem()).append(": ");
                if (axis >= 5 && axis <= 7) text.append(values[i] > 0 ? "Match enabled" : "Off");
                else text.append(String.format(java.util.Locale.US, "%.1f%%", total == 0 ? 0 : (values[i] / largest) / total * 100));
                text.append("\n");
            }
            summary.setText(text.toString().trim());
        };
        java.util.function.BiConsumer<Integer, Double> addFactor = (axis, weight) -> {
            LinearLayout row = new LinearLayout(requireContext()); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            Spinner type = new Spinner(requireContext()); ArrayList<String> labels = new ArrayList<>(); for (int value : AXIS_ORDER) labels.add(AXES[value]);
            type.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels));
            for (int i = 0; i < AXIS_ORDER.length; i++) if (AXIS_ORDER[i] == axis) type.setSelection(i);
            EditText input = numericInput("Weight", "" + weight, true); Button remove = new Button(requireContext()); remove.setText("×"); remove.setContentDescription("Remove factor"); remove.setMinWidth(0); remove.setMinimumWidth(0);
            types.add(type); weights.add(input); row.addView(type, new LinearLayout.LayoutParams(0, -2, 2)); row.addView(input, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(remove, new LinearLayout.LayoutParams(Math.round(48 * getResources().getDisplayMetrics().density), -2)); factors.addView(row);
            remove.setOnClickListener(v -> { int index = types.indexOf(type); types.remove(index); weights.remove(index); factors.removeView(row); update.run(); });
            input.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence t, int start, int count, int after) {}
                public void onTextChanged(CharSequence t, int start, int before, int count) { update.run(); }
                public void afterTextChanged(android.text.Editable t) {}
            });
            type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { update.run(); }
                public void onNothingSelected(AdapterView<?> parent) {}
            }); update.run();
        };
        if (hasMetric()) { for (int axis : AXIS_ORDER) if (metricWeights[axis] > 0) addFactor.accept(axis, metricWeights[axis]); }
        else for (int axis : dimensions()) addFactor.accept(axis, 1.0);
        Button add = new Button(requireContext()); add.setText("Add factor"); add.setAllCaps(false); form.addView(add);
        add.setOnClickListener(v -> {
            for (int axis : AXIS_ORDER) { boolean used = false; for (Spinner type : types) if (AXIS_ORDER[type.getSelectedItemPosition()] == axis) used = true;
                if (!used) { addFactor.accept(axis, 1.0); return; } }
        });
        ScrollView scroll = new ScrollView(requireContext()); scroll.addView(form); choosingItem = false;
        chooser = new MaterialAlertDialogBuilder(requireContext()).setTitle("Adjust similarity").setView(scroll)
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).show();
        chooser.getButton(-1).setOnClickListener(v -> {
            double[] selected = new double[AXES.length]; boolean[] used = new boolean[AXES.length]; boolean any = false;
            for (int i = 0; i < types.size(); i++) {
                int axis = AXIS_ORDER[types.get(i).getSelectedItemPosition()];
                if (used[axis]) { weights.get(i).setError("Use each factor only once."); return; } used[axis] = true;
                try { selected[axis] = Double.parseDouble(weights.get(i).getText().toString()); if (!Double.isFinite(selected[axis]) || selected[axis] < 0) throw new NumberFormatException(); }
                catch (NumberFormatException error) { weights.get(i).setError("Enter a nonnegative weight."); return; }
                any |= selected[axis] > 0;
            }
            if (!any) { summary.setText("Add at least one positive weight."); return; }
            metricWeights = selected; chooser.dismiss(); redraw();
        });
    }
    private ArrayList<Integer> dimensions() {
        ArrayList<Integer> selected = new ArrayList<>();
        for (Spinner selector : new Spinner[]{x, y, z, w}) {
            int position = selector.getSelectedItemPosition() - 1;
            int value = position < 0 ? -1 : AXIS_ORDER[position];
            if (value >= 0 && !selected.contains(value)) selected.add(value);
        }
        return selected;
    }
    private void label(String text) { TextView label = new TextView(requireContext()); label.setText(text); label.setPadding(0, 12, 0, 12); body.addView(label); }
    private void openGroup(ArrayList<Item> group) {
        if (!unlocked()) { readingsChanged(); return; }
        if (group.size() == 1) { open(group.get(0)); return; }
        String[] names = new String[group.size()]; for (int i = 0; i < names.length; i++) names[i] = group.get(i).title;
        choosingItem = true;
        chooser = new MaterialAlertDialogBuilder(requireContext()).setTitle("Items at this point")
                .setItems(names, (d, which) -> open(group.get(which))).show();
    }
    private void open(Item item) {
        if (!isResumed() || !unlocked()) { readingsChanged(); return; }
        opened = new LibraryPage(this, libraryId, libraryTitle); opened.openFromWeb(item.id);
    }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); saveSelection(state);
    }
    private void saveSelection(Bundle state) {
        state.putLong("library", libraryId); state.putBoolean("figure", showingFigure);
        state.putInt("x", x.getSelectedItemPosition()); state.putInt("y", y.getSelectedItemPosition());
        state.putInt("z", z.getSelectedItemPosition()); state.putInt("w", w.getSelectedItemPosition());
        state.putLong("shuffle_seed", shuffleSeed);
        state.putBoolean("neighbors", neighbors); state.putInt("k", neighborCount); state.putInt("limit", displayLimit);
        state.putDoubleArray("weights", metricWeights.clone()); state.putStringArrayList("hidden", new ArrayList<>(hiddenItems));
        webState.state.set("selection", new Bundle(state));
    }
    @Override public void onPause() {
        saveSelection(new Bundle());
        if (opened != null) opened.suspendContent();
        if (body != null) body.setVisibility(View.INVISIBLE);
        if (chooser != null) chooser.dismiss();
        super.onPause();
    }
    @Override public void onDestroyView() {
        if (opened != null) { opened.dismiss(); opened = null; }
        if (chooser != null) chooser.dismiss();
        body = null; rendered = ""; super.onDestroyView();
    }
}
