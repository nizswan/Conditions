package com.example.conditions;

import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/** The condition builder lives inside the library editor. */
final class LibraryEditor {
    private final LiveConditionsFragment host;
    private final String username;
    private final long id;
    private final Runnable saved;
    private JSONObject pieces;
    private byte[] cover;
    private EditText title, description, expression;
    private boolean changingExpression;
    private LinearLayout pieceList;
    private androidx.activity.ComponentDialog dialog;

    LibraryEditor(LiveConditionsFragment host, long id, String name, JSONObject rule, Runnable saved) {
        this.host = host; this.id = id; this.saved = saved;
        username = host.requireArguments().getString("username", "");
        pieces = ConditionRule.read(rule.toString()).optJSONObject("pieces");
        if (pieces == null) pieces = new JSONObject();
        LinearLayout page = column();
        dialog = new androidx.activity.ComponentDialog(host.requireContext(), R.style.Theme_Conditions);
        dialog.setCanceledOnTouchOutside(false);
        ImageButton back = new ImageButton(host.requireContext());
        back.setImageResource(R.drawable.icon_back);
        back.setBackgroundResource(android.R.color.transparent);
        back.setContentDescription("Back");
        int backSize = (int) (48 * host.getResources().getDisplayMetrics().density);
        int backPadding = backSize / 4;
        back.setPadding(backPadding, backPadding, backPadding, backPadding);
        back.setOnClickListener(v -> confirmExit());
        page.addView(back, new LinearLayout.LayoutParams(backSize, backSize));
        LinearLayout form = column();
        if (id == 0) heading(form, "New Library");
        title = input(form, "Library title", name, false);
        description = input(form, "Description (optional)", "", false);
        description.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        description.setSingleLine(false); description.setMinLines(2);
        if (id != 0) {
            title.setVisibility(View.GONE);
            description.setVisibility(View.GONE);
        }
        if (id == 0) {
            ImageView preview = new ImageView(host.requireContext());
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP); preview.setVisibility(View.GONE);
            preview.setContentDescription("Library cover preview");
            form.addView(preview, new LinearLayout.LayoutParams(-1, (int) (120 * host.getResources().getDisplayMetrics().density)));
            Button choose = button(form, "Add cover photo (optional)", () -> {});
            Button remove = button(form, "Remove cover photo", () -> {});
            remove.setVisibility(View.GONE);
            choose.setOnClickListener(v -> host.chooseCover(photo -> {
                if (!dialog.isShowing()) return;
                cover = photo;
                preview.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.length));
                preview.setVisibility(View.VISIBLE);
                choose.setText("Change cover photo"); remove.setVisibility(View.VISIBLE);
            }));
            remove.setOnClickListener(v -> {
                cover = null; preview.setImageDrawable(null); preview.setVisibility(View.GONE);
                choose.setText("Add cover photo (optional)"); remove.setVisibility(View.GONE);
            });
        }
        heading(form, "Condition Creator");
        text(form, "Add pieces, then combine their letters. NOT runs first, then AND, then OR. Parentheses change the order. Example: (A OR B) AND NOT C.");
        expression = new androidx.appcompat.widget.AppCompatEditText(host.requireContext()) {
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info) { return null; }
            @Override public boolean onTextContextMenuItem(int action) { return false; }
            @Override public boolean onDragEvent(android.view.DragEvent event) { return false; }
        };
        expression.setHint("Expression"); expression.setSingleLine(true);
        expression.setText(rule.optString("expression")); expression.setSelection(expression.length());
        expression.setShowSoftInputOnFocus(false); expression.setLongClickable(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            expression.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        expression.setFilters(new android.text.InputFilter[]{(source, start, end, dest, from, to) ->
                changingExpression ? null : dest.subSequence(from, to)});
        expression.setOnFocusChangeListener((v, focused) -> {
            if (focused) ((android.view.inputmethod.InputMethodManager) host.requireContext()
                    .getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(expression.getWindowToken(), 0);
        });
        form.addView(expression);
        LinearLayout operators = new LinearLayout(host.requireContext()); form.addView(operators);
        for (String op : new String[]{"AND", "OR", "NOT", "(", ")"}) button(operators, op, () -> insert(op));
        button(operators, "⌫", this::backspace).setContentDescription("Delete previous piece or operator");
        button(form, "Add condition piece", this::chooseType);
        pieceList = column(); pieceList.setPadding(0, 0, 0, 0); form.addView(pieceList);
        ScrollView scroll = new ScrollView(host.requireContext()); scroll.addView(form);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        com.google.android.material.button.MaterialButton save = new com.google.android.material.button.MaterialButton(host.requireContext());
        save.setText("Save"); save.setAllCaps(false);
        page.addView(save, new LinearLayout.LayoutParams(-1, -2));
        save.setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty() || title.length() > 100) { title.setError("Enter a title (up to 100 characters)."); return; }
            try (LibraryStore store = new LibraryStore(host.requireContext())) {
                store.save(username, id, title.getText().toString(), rule(), false,
                        id == 0 ? description.getText().toString() : null, cover);
                dialog.dismiss(); saved.run();
            } catch (Exception error) { expression.setError(error.getMessage()); }
        });
        dialog.setContentView(page);
        dialog.getOnBackPressedDispatcher().addCallback(new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { confirmExit(); }
        });
        int padding = (int) (12 * host.getResources().getDisplayMetrics().density);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialog.getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(page, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout() | androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(padding + bars.left, bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        host.getViewLifecycleOwner().getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onDestroy(androidx.lifecycle.LifecycleOwner owner) { dialog.dismiss(); }
        });
        renderPieces(); dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void confirmExit() {
        new MaterialAlertDialogBuilder(host.requireContext()).setTitle("Leave library editor?")
                .setMessage("Unsaved library changes will be lost.")
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Leave", (d, which) -> dialog.dismiss()).show();
    }
    private void heading(LinearLayout layout, String value) {
        TextView heading = new TextView(host.requireContext());
        heading.setText(value); heading.setTextSize(28);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        int space = (int) (16 * host.getResources().getDisplayMetrics().density);
        heading.setPadding(0, space, 0, space);
        layout.addView(heading);
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(host.requireContext()); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * host.getResources().getDisplayMetrics().density); layout.setPadding(padding, padding, padding, padding);
        return layout;
    }
    private void text(LinearLayout layout, String value) { TextView v = new TextView(host.requireContext()); v.setText(value); layout.addView(v); }
    private EditText input(LinearLayout layout, String hint, String value, boolean number) {
        EditText v = new EditText(host.requireContext()); v.setHint(hint); v.setText(value); v.setSingleLine(true);
        v.setInputType(number ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED : InputType.TYPE_CLASS_TEXT);
        layout.addView(v); return v;
    }
    private Button button(LinearLayout layout, String label, Runnable action) {
        Button b = new Button(host.requireContext()); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); if (layout.getOrientation() == LinearLayout.HORIZONTAL) { b.setMinWidth(0); b.setMinimumWidth(0); b.setTextSize(11); b.setPadding(0, 0, 0, 0); layout.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); }
        else layout.addView(b); return b;
    }
    private int[] expressionSelection() {
        int start = Math.max(0, Math.min(expression.getSelectionStart(), expression.getSelectionEnd()));
        int end = Math.max(start, Math.max(expression.getSelectionStart(), expression.getSelectionEnd()));
        String text = expression.getText().toString();
        if (start != end) while (start > 0 && start < text.length()
                && Character.isLetterOrDigit(text.charAt(start)) && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
        while (end > 0 && end < text.length() && Character.isLetterOrDigit(text.charAt(end - 1))
                && Character.isLetterOrDigit(text.charAt(end))) end++;
        if (expression.getSelectionStart() == expression.getSelectionEnd()) start = end;
        return new int[]{start, end};
    }
    private void replaceExpression(int start, int end, String value) {
        changingExpression = true;
        try {
            expression.getText().replace(start, end, value);
            expression.requestFocus(); expression.setSelection(start + value.length()); expression.setError(null);
        } finally { changingExpression = false; }
    }
    private void insert(String value) {
        int[] selection = expressionSelection();
        replaceExpression(selection[0], selection[1], " " + value + " ");
    }
    private void backspace() {
        int[] selection = expressionSelection();
        int start = selection[0], end = selection[1];
        if (start == end) {
            String text = expression.getText().toString();
            while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) start--;
            if (start > 0) {
                start--;
                if (Character.isLetterOrDigit(text.charAt(start)))
                    while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
            }
        }
        replaceExpression(start, end, "");
    }
    private void add(JSONObject piece) {
        try {
            int n = 0; String key;
            do { key = n < 26 ? "" + (char) ('A' + n) : "P" + n; n++; } while (pieces.has(key));
            pieces.put(key, piece); renderPieces();
            insert(key);
        } catch (Exception error) { message("Could not add this piece."); }
    }
    private void renderPieces() {
        pieceList.removeAllViews();
        java.util.Iterator<String> keys = pieces.keys();
        while (keys.hasNext()) {
            String key = keys.next(); JSONObject p = pieces.optJSONObject(key);
            LinearLayout row = new LinearLayout(host.requireContext());
            row.setGravity(android.view.Gravity.CENTER_VERTICAL); pieceList.addView(row);
            Button b = new Button(host.requireContext()); b.setAllCaps(false);
            b.setText(key + " = " + p.optString("label", p.optString("expression")));
            b.setOnClickListener(v -> insert(key));
            row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            if (!p.has("expression")) {
                ImageButton edit = new ImageButton(host.requireContext());
                edit.setImageResource(android.R.drawable.ic_menu_edit);
                edit.setBackgroundResource(android.R.color.transparent);
                edit.setContentDescription("Edit condition " + key);
                int size = (int) (48 * host.getResources().getDisplayMetrics().density);
                row.addView(edit, new LinearLayout.LayoutParams(size, size));
                edit.setOnClickListener(v -> primitive(p.optString("type"), key));
            }
            b.setOnLongClickListener(v -> { pieces.remove(key); renderPieces(); return true; });
        }
    }
    private void putPiece(String key, JSONObject piece) throws Exception {
        if (key == null) add(piece);
        else { pieces.put(key, piece); renderPieces(); }
    }
    private JSONObject rule() throws Exception {
        JSONObject rule = new JSONObject(); rule.put("expression", expression.getText().toString().trim().toUpperCase(Locale.ROOT)); rule.put("pieces", pieces);
        ConditionRule.validate(rule);
        return rule;
    }
    private void chooseType() {
        String[] types = {"time", "battery", "bluetooth", "wifi", "location", "history", "weather", "temperature"};
        String[] labels = {"Time of day", "Battery (%)", "Connected Bluetooth device", "Connected Wi-Fi network", "Location radius (meters)", "Minutes since previous visit", "Weather type", "Temperature (Fahrenheit)"};
        ArrayList<String> available = new ArrayList<>(), names = new ArrayList<>();
        ConditionSettings settings = new ConditionSettings(host.requireContext(), username);
        for (int i = 0; i < types.length; i++) {
            String setting = (types[i].equals("weather") || types[i].equals("temperature")) ? "environment" : types[i];
            if (settings.isEnabled(setting)) { available.add(types[i]); names.add(labels[i]); }
        }
        if (available.isEmpty()) { message("Enable condition types in Settings first."); return; }
        new MaterialAlertDialogBuilder(host.requireContext()).setTitle("Condition type (enabled in Settings)")
                .setItems(names.toArray(new String[0]), (d, which) -> primitive(available.get(which), null)).show();
    }
    private String conditionName(String type) {
        if (type.equals("wifi")) return "Wi-Fi";
        return type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1);
    }
    private void primitive(String type, String key) {
        JSONObject existing = key == null ? null : pieces.optJSONObject(key);
        if (type.equals("location") && existing != null) {
            new MaterialAlertDialogBuilder(host.requireContext()).setTitle("Edit " + key)
                    .setItems(new String[]{"Edit place name / radius", "Change location"}, (d, which) -> {
                        if (which == 0) location(ConditionRule.read(existing.toString()), key);
                        else chooseObserved(type, key);
                    }).show();
            return;
        }
        if (type.equals("bluetooth") || type.equals("wifi") || type.equals("location")) { chooseObserved(type, key); return; }
        if (type.equals("weather")) {
            String[] values = new String[]{"Sunny", "Clear", "Cloudy", "Foggy", "Rainy", "Snowy", "Thunderstorms"};
            int selected = existing == null ? -1 : java.util.Arrays.asList(values).indexOf(existing.optString("value"));
            new MaterialAlertDialogBuilder(host.requireContext()).setTitle(key == null ? conditionName(type) : "Edit " + key)
                    .setSingleChoiceItems(values, selected, (d, which) -> {
                JSONObject p = new JSONObject(); try { p.put("type", type).put("value", values[which]).put("label", conditionName(type) + " is " + values[which]); putPiece(key, p); d.dismiss(); } catch (Exception ignored) {}
            }).show();
            return;
        }
        LinearLayout form = column(); boolean time = type.equals("time");
        String units = time ? "24-hour HH:mm, device local time\nA start after the end crosses midnight"
                : type.equals("battery") ? "Battery percent, 0 to 100"
                : type.equals("history") ? "Minutes since the previous visit\nFirst visit has no previous reading" : "Degrees Fahrenheit";
        text(form, units);
        EditText low = input(form, "From", time ? "15:00" : "0", !time);
        EditText high = input(form, "To", time ? "18:00" : type.equals("battery") ? "100" : "60", !time);
        if (existing != null) {
            low.setText(bound(existing.optDouble("min"), time));
            high.setText(bound(existing.optDouble("max"), time));
        }
        AlertDialog d = new MaterialAlertDialogBuilder(host.requireContext()).setTitle(key == null ? conditionName(type) : "Edit " + key)
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton(key == null ? "Add piece" : "Update piece", null).create();
        d.setOnShowListener(v -> d.getButton(-1).setOnClickListener(b -> {
            try {
                double min = time ? minutes(low.getText().toString()) : number(low);
                double max = time ? minutes(high.getText().toString()) : number(high);
                if (!time && min > max || type.equals("battery") && (min < 0 || max > 100) || type.equals("history") && min < 0) throw new IllegalArgumentException("Enter a valid range.");
                JSONObject p = new JSONObject().put("type", type).put("min", min).put("max", max)
                        .put("label", conditionName(type) + ": " + low.getText() + " to " + high.getText() + (type.equals("temperature") ? " F" : type.equals("history") ? " minutes" : ""));
                putPiece(key, p); d.dismiss();
            } catch (Exception error) { low.setError(error.getMessage()); }
        })); d.show();
    }
    private String bound(double value, boolean time) {
        if (time) return String.format(Locale.US, "%02d:%02d", (int) value / 60, (int) value % 60);
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
    private int minutes(String text) {
        if (!text.matches("([01]?[0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("Use HH:mm, such as 15:00.");
        String[] parts = text.split(":"); return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
    private double number(EditText text) {
        try { double n = Double.parseDouble(text.getText().toString()); if (Double.isFinite(n)) return n; }
        catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Enter a number.");
    }
    private void chooseObserved(String type, String key) {
        LinkedHashMap<String, JSONObject> choices = new LinkedHashMap<>();
        if (key != null) {
            JSONObject existing = pieces.optJSONObject(key);
            choices.put("Selected: " + existing.optString("label"), existing);
        }
        try (LibraryStore store = new LibraryStore(host.requireContext())) {
            if (type.equals("bluetooth")) {
                choices.put("None — no Bluetooth devices connected", new JSONObject()
                        .put("type", "bluetooth").put("value", "none")
                        .put("label", "None — no Bluetooth devices connected"));
            }
            JSONArray old = store.choices(username, type);
            for (int i = 0; i < old.length(); i++) { JSONObject p = old.getJSONObject(i); choices.put(p.optString("label"), p); }
            boolean fresh = host.signals.updated != 0 && android.os.SystemClock.elapsedRealtime() - host.signals.updated < 10000;
            if (fresh && type.equals("bluetooth")) {
                for (java.util.Map.Entry<String, String> e : host.signals.devices.entrySet()) {
                    String label = e.getValue() + " (" + e.getKey() + ")";
                    choices.put("Current: " + label, new JSONObject().put("type", type).put("value", e.getKey()).put("label", label));
                }
            } else if (fresh && type.equals("wifi") && host.signals.wifi != null && !host.signals.wifi.isEmpty()) {
                String ssid = host.signals.wifi;
                choices.put("Current: " + ssid, new JSONObject().put("type", type).put("value", ssid).put("label", "Wi-Fi: " + ssid));
            } else if (fresh && type.equals("location") && host.signals.location != null) {
                android.location.Location loc = host.signals.location;
                choices.put("Current location (accuracy " + Math.round(loc.getAccuracy()) + " m)", new JSONObject().put("type", type).put("lat", loc.getLatitude()).put("lon", loc.getLongitude()).put("radius", 0).put("label", "Current location"));
            }
        } catch (Exception error) { message("Could not load choices."); return; }
        if (choices.isEmpty()) { message("No saved choices or current reading. Enable the signal and its permissions, then wait for a reading."); return; }
        ArrayList<JSONObject> values = new ArrayList<>(choices.values());
        new MaterialAlertDialogBuilder(host.requireContext()).setTitle(conditionName(type))
                .setItems(choices.keySet().toArray(new String[0]), (d, which) -> {
                    JSONObject p = ConditionRule.read(values.get(which).toString());
                    if (type.equals("location")) location(p, key);
                    else try { putPiece(key, p); } catch (Exception error) { message("Could not update this piece."); }
                }).show();
    }
    private void location(JSONObject p, String key) {
        LinearLayout form = column();
        EditText name = input(form, "Place name", p.optString("place", ""), false);
        text(form, String.format(Locale.US, "Center: %.5f, %.5f\nRadius in meters\nChoose a radius larger than GPS accuracy\nAn uncertain boundary stays locked", p.optDouble("lat"), p.optDouble("lon")));
        EditText radius = input(form, "Radius in meters", "" + p.optDouble("radius", 0), true);
        AlertDialog d = new MaterialAlertDialogBuilder(host.requireContext()).setTitle(key == null ? "Location" : "Edit " + key).setView(form).setNegativeButton("Cancel", null).setPositiveButton(key == null ? "Add piece" : "Update piece", null).create();
        d.setOnShowListener(v -> d.getButton(-1).setOnClickListener(b -> {
            try {
                double r = number(radius); String place = name.getText().toString().trim();
                if (r < 0 || place.isEmpty() || place.length() > 100) throw new IllegalArgumentException("Enter a place name and a radius of zero or more.");
                p.put("radius", r).put("place", place).put("label", place + ": within " + r + " m"); putPiece(key, p); d.dismiss();
            } catch (Exception error) { radius.setError(error.getMessage()); }
        })); d.show();
    }
    private void message(String message) { Toast.makeText(host.requireContext(), message, Toast.LENGTH_LONG).show(); }
}
