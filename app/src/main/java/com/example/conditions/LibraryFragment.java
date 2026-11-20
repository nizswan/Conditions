package com.example.conditions;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import android.database.Cursor;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;

public class LibraryFragment extends LiveConditionsFragment {
    private String query = "";
    private LibraryPage opened;
    private long openedId;
    private String rendered = "";
    public LibraryFragment() { super(R.layout.fragment_library); }
    private String username() { return requireArguments().getString("username", ""); }
    @Override public void onViewCreated(View view, Bundle state) {
        super.onViewCreated(view, state);
        view.findViewById(R.id.add_library).setOnClickListener(v -> { new LibraryEditor(this, 0, "", new JSONObject(), this::reload); });
        ((RadioGroup) view.findViewById(R.id.library_filters)).setOnCheckedChangeListener((group, id) -> reload());
        view.findViewById(R.id.search).setOnClickListener(v -> {
            EditText input = new EditText(requireContext()); input.setSingleLine(true); input.setHint("Library title"); input.setText(query);
            new MaterialAlertDialogBuilder(requireContext()).setTitle("Search libraries").setView(input)
                    .setPositiveButton("Search", (d, w) -> { query = input.getText().toString().trim(); reload(); })
                    .setNeutralButton("Clear", (d, w) -> { query = ""; reload(); }).setNegativeButton("Cancel", null).show();
        });
        reload();
    }
    private void reload() { rendered = ""; readingsChanged(); }
    @Override protected void readingsChanged() {
        if (getView() == null) return;
        TextView searchSummary = getView().findViewById(R.id.library_search_summary);
        searchSummary.setText(query.isEmpty() ? "" : "Results for: “" + query + "”");
        searchSummary.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        int filter = ((RadioGroup) getView().findViewById(R.id.library_filters)).getCheckedRadioButtonId();
        StringBuilder signature = new StringBuilder(query + filter);
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        java.util.ArrayList<String> titles = new java.util.ArrayList<>();
        java.util.ArrayList<String> descriptions = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> itemCounts = new java.util.ArrayList<>();
        java.util.ArrayList<byte[]> covers = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<JSONObject> rules = new java.util.ArrayList<>();
        java.util.ArrayList<ConditionRule.State> states = new java.util.ArrayList<>();
        boolean stillOpen = false;
        int activeCount = 0, hiddenCount = 0;
        try (LibraryStore store = new LibraryStore(requireContext()); Cursor rows = store.forUser(username())) {
            while (rows.moveToNext()) {
                long id = rows.getLong(0); String title = rows.getString(1); JSONObject rule = ConditionRule.read(rows.getString(2));
                ConditionRule.State status = ConditionRule.evaluate(rule, signals);
                if (status == ConditionRule.State.TRUE) activeCount++; else hiddenCount++;
                if (id == openedId && status == ConditionRule.State.TRUE) stillOpen = true;
                if (filter == R.id.filter_active && status != ConditionRule.State.TRUE) continue;
                if (filter == R.id.filter_hidden && status == ConditionRule.State.TRUE) continue;
                if (!title.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) continue;
                String label = conditionLabel(rule, status);
                itemCounts.add(rows.getInt(5));
                signature.append(rows.getInt(5));
                covers.add(rows.isNull(4) ? null : rows.getBlob(4));
                signature.append(java.util.Arrays.hashCode(covers.get(covers.size() - 1)));
                ids.add(id); titles.add(title); descriptions.add(rows.getString(3)); labels.add(label); rules.add(rule); states.add(status);
                signature.append(id).append(title).append(rows.getString(3)).append(rule).append(status).append(label);
            }
        }
        ((RadioButton) getView().findViewById(R.id.filter_active)).setText("Active (" + activeCount + ")");
        ((RadioButton) getView().findViewById(R.id.filter_hidden)).setText("Hidden (" + hiddenCount + ")");
        ((RadioButton) getView().findViewById(R.id.filter_all)).setText("All (" + (activeCount + hiddenCount) + ")");
        if (opened != null && opened.isShowing()) {
            if (!stillOpen) {
                opened.dismiss(); opened = null;
                Toast.makeText(requireContext(), "Library locked: its conditions no longer hold.", Toast.LENGTH_LONG).show();
            } else opened.resumeContent();
        }
        if (signature.toString().equals(rendered)) return;
        rendered = signature.toString();
        LinearLayout list = getView().findViewById(R.id.library_list); list.removeAllViews();
        if (ids.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(filter == R.id.filter_active ? "No active libraries"
                    : filter == R.id.filter_hidden ? "No hidden libraries" : "No libraries");
            list.addView(empty);
        }
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i); String title = titles.get(i); JSONObject rule = rules.get(i); ConditionRule.State status = states.get(i);
            String description = descriptions.get(i);
            boolean active = status == ConditionRule.State.TRUE;
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(16));
            card.setMinimumHeight(dp(196));
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{android.graphics.Color.rgb(239, 240, 242), active ? 0xFFA7DDB3 : 0xFFF0ABAB});
            background.setCornerRadius(dp(16)); card.setBackground(LibraryCover.background(covers.get(i), background, dp(16)));
            android.util.TypedValue ripple = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            card.setForeground(androidx.core.content.ContextCompat.getDrawable(requireContext(), ripple.resourceId));
            LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2); cardSize.bottomMargin = dp(12);
            list.addView(card, cardSize);
            card.setOnClickListener(v -> open(id, title, rule));
            card.setFocusable(true);
            LinearLayout top = new LinearLayout(requireContext()); top.setGravity(android.view.Gravity.TOP);
            card.addView(top);
            TextView name = cardText(title, 24); name.setTypeface(null, android.graphics.Typeface.BOLD);
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton manage = new ImageButton(requireContext());
            manage.setImageResource(android.R.drawable.ic_menu_edit);
            manage.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF30343B));
            manage.setBackgroundResource(ripple.resourceId); manage.setPadding(dp(12), dp(12), dp(12), dp(12));
            manage.setContentDescription("Edit " + title);
            top.addView(manage, new LinearLayout.LayoutParams(dp(48), dp(48)));
            manage.setOnClickListener(v -> manage(id, title, description, rule));
            TextView caption = cardText(description, 14);
            caption.setPadding(0, dp(8), 0, dp(16));
            card.addView(caption, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout bottom = new LinearLayout(requireContext()); bottom.setGravity(android.view.Gravity.BOTTOM);
            card.addView(bottom);
            TextView condition = cardText(labels.get(i), 12);
            condition.setTextColor(active ? 0xFF215A32 : 0xFF8A2020);
            condition.setPadding(dp(10), dp(7), dp(10), dp(7));
            condition.setMaxLines(3); condition.setEllipsize(android.text.TextUtils.TruncateAt.END);
            android.graphics.drawable.GradientDrawable bubble = new android.graphics.drawable.GradientDrawable();
            bubble.setColor(active ? 0xFFE1F3E5 : 0xFFFFE3E3); bubble.setCornerRadius(dp(12));
            condition.setBackground(bubble);
            bottom.addView(condition, new LinearLayout.LayoutParams(0, -2, 1));
            int itemCount = itemCounts.get(i);
            TextView count = cardText(itemCount + (itemCount == 1 ? " item" : " items"), 12); count.setPadding(dp(12), 0, 0, dp(7));
            bottom.addView(count);

        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView cardText(String text, int size) {
        TextView view = new TextView(requireContext()); view.setText(text); view.setTextSize(size); view.setTextColor(0xFF252A31);
        return view;
    }
    private String conditionLabel(JSONObject rule, ConditionRule.State state) {
        if (state == ConditionRule.State.TRUE) return "Conditions met";
        JSONObject pieces = rule.optJSONObject("pieces");
        if (pieces == null) return "No condition set";
        // Keep NOT and parentheses visible so the reason matches the whole expression.
        java.util.regex.Matcher tokens = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*")
                .matcher(rule.optString("expression").toUpperCase(java.util.Locale.ROOT));
        StringBuffer label = new StringBuffer();
        while (tokens.find()) {
            JSONObject piece = pieces.optJSONObject(tokens.group());
            String replacement = piece == null ? tokens.group() : piece.optString("label", tokens.group());
            tokens.appendReplacement(label, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        tokens.appendTail(label);
        return (state == ConditionRule.State.UNKNOWN ? "Unavailable: " : "Not met: ") + label;
    }
    private void manage(long id, String title, String description, JSONObject rule) {
        new MaterialAlertDialogBuilder(requireContext()).setTitle(title)
                .setItems(new String[]{"Change title", "Change description", "Change conditions", "Delete library", "Cover photo"}, (d, which) -> {
                    if (which < 2) editText(id, which == 1, which == 1 ? description : title);
                    else if (which == 2) new LibraryEditor(this, id, title, rule, this::reload);
                    else if (which == 4) new MaterialAlertDialogBuilder(requireContext()).setTitle("Cover photo")
                            .setItems(new String[]{"Choose photo", "Remove photo"}, (coverDialog, action) -> {
                                if (action == 0) chooseCover(photo -> saveCover(id, photo));
                                else saveCover(id, null);
                            }).show();
                    else new MaterialAlertDialogBuilder(requireContext()).setTitle("Delete " + title + "?")
                            .setMessage("Delete this library?")
                            .setNegativeButton("Keep library", null).setPositiveButton("Delete", (confirm, choice) -> {
                                try (LibraryStore store = new LibraryStore(requireContext())) { store.delete(username(), id); }
                                reload();
                            }).show();
                }).show();
    }
    private void saveCover(long id, byte[] photo) {
        try (LibraryStore store = new LibraryStore(requireContext())) {
            store.setCover(username(), id, photo); reload();
        } catch (Exception error) { Toast.makeText(requireContext(), "Could not save cover photo.", Toast.LENGTH_LONG).show(); }
    }
    private void editText(long id, boolean description, String value) {
        EditText input = new EditText(requireContext()); input.setText(value);
        input.setSingleLine(!description);
        if (description) { input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); input.setMinLines(3); }
        AlertDialog editor = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(description ? "Change description" : "Change title").setView(input)
                .setNegativeButton("Back", null).setPositiveButton("Save", null).create();
        editor.setOnShowListener(d -> editor.getButton(-1).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (!description && (text.isEmpty() || text.length() > 100)) { input.setError("Enter a title (up to 100 characters)."); return; }
            try (LibraryStore store = new LibraryStore(requireContext())) {
                store.editText(username(), id, description, text); editor.dismiss(); reload();
            } catch (Exception error) { input.setError("Could not save changes."); }
        }));
        editor.show();
    }
    private void open(long id, String title, JSONObject rule) {
        ConditionRule.State state = ConditionRule.evaluate(rule, signals);
        if (state != ConditionRule.State.TRUE) {
            StringBuilder explanation = new StringBuilder("This library stays locked until its expression is true.\n");
            JSONObject pieces = rule.optJSONObject("pieces");
            if (pieces != null) for (String key : ConditionRule.used(rule)) {
                JSONObject p = pieces.optJSONObject(key);
                if (p != null) explanation.append("\n").append(key).append(": ").append(p.optString("label", p.optString("expression"))).append(" - ")
                        .append(p.has("expression") ? ConditionRule.evaluate(p, signals) : signals.evaluate(p));
            }
            new MaterialAlertDialogBuilder(requireContext()).setTitle("Locked: " + title).setMessage(explanation).setPositiveButton("OK", null).show();
            return;
        }
        openedId = id;
        opened = new LibraryPage(this, id, title);
    }
    @Override public void onPause() {
        if (opened != null) opened.suspendContent();
        super.onPause();
    }
    @Override public void onDestroyView() {
        if (opened != null) { opened.dismiss(); opened = null; }
        super.onDestroyView();
    }
}
