package com.example.conditions;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.activity.ComponentDialog;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Locale;

/** Individual files in one library. */
final class LibraryPage {
    private final LiveConditionsFragment host;
    private final long libraryId;
    private final String username, libraryTitle;
    private final ComponentDialog dialog;
    private final LinearLayout body, toolbar;
    private final TextView heading, searchSummary;
    private final ImageButton sort, search, add, edit;
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<Item> shown = new ArrayList<>();
    private final java.util.concurrent.ExecutorService previews = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private ViewPager pager;
    private String query = "";
    private int sortOrder = 0;
    private android.media.MediaPlayer audio;
    private Button playingButton;
    private boolean suspended;
    private boolean fromWeb;
    private final ArrayList<androidx.appcompat.app.AlertDialog> childDialogs = new ArrayList<>();

    private static class Item {
        String title, id, name, uri;
        long submittedAt;
        org.json.JSONObject context;
    }

    LibraryPage(LiveConditionsFragment host, long libraryId, String title) {
        this.host = host; this.libraryId = libraryId; libraryTitle = title;
        username = host.requireArguments().getString("username", "");
        loadItems();
        dialog = new ComponentDialog(host.requireContext(), R.style.Theme_Conditions);
        LinearLayout page = column(); page.setBackgroundColor(0xFFF4F5F7);
        toolbar = new LinearLayout(host.requireContext()); toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        icon(toolbar, R.drawable.icon_back, "Back", this::back);
        heading = text("", 24); heading.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        sort = icon(toolbar, R.drawable.icon_sort, "Sort items", this::sort);
        search = icon(toolbar, R.drawable.icon_search, "Search items", this::search);
        add = icon(toolbar, R.drawable.icon_create, "Add item", () -> {
            if (!unlocked()) return;
            dismiss(); ((MainActivity) host.requireActivity()).createPayloadInLibrary(libraryId);
        });
        edit = icon(toolbar, android.R.drawable.ic_menu_edit, "Edit item", () -> {
            if (pager != null) manage(shown.get(pager.getCurrentItem()));
        });
        searchSummary = text("", 12); searchSummary.setPadding(dp(12), 0, dp(12), dp(8)); page.addView(searchSummary);
        body = column(); body.setPadding(dp(12), dp(8), dp(12), 0);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(page);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialog.getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(page, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(dp(12) + bars.left, bars.top, dp(12) + bars.right, dp(12) + bars.bottom);
            return insets;
        });
        dialog.getOnBackPressedDispatcher().addCallback(new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        dialog.setOnDismissListener(d -> {
            stopAudio(); previews.shutdownNow();
            for (androidx.appcompat.app.AlertDialog child : new ArrayList<>(childDialogs)) child.dismiss();
        });
        folders(); dialog.show(); dialog.getWindow().setLayout(-1, -1);
    }

    private void loadItems() {
        items.clear();
        try (LibraryStore store = new LibraryStore(host.requireContext()); Cursor rows = store.items(username, libraryId)) {
            while (rows.moveToNext()) {
                Item item = new Item(); item.title = rows.getString(0); item.uri = rows.getString(1);
                item.name = rows.getString(2); item.id = rows.getString(3);
                item.context = ConditionRule.read(rows.getString(4));
                item.submittedAt = item.context.optLong("submitted_at", 0);
                items.add(item);
            }
        }
    }

    boolean isShowing() { return dialog.isShowing(); }
    void dismiss() { dialog.dismiss(); }
    void suspendContent() { suspended = true; body.setVisibility(View.INVISIBLE); stopAudio(); }
    void resumeContent() { suspended = false; body.setVisibility(View.VISIBLE); }
    private boolean unlocked() {
        if (suspended || !host.isResumed()) return false;
        try (LibraryStore store = new LibraryStore(host.requireContext()); Cursor rows = store.forUser(username)) {
            while (rows.moveToNext()) if (rows.getLong(0) == libraryId)
                return ConditionRule.evaluate(ConditionRule.read(rows.getString(2)), host.signals) == ConditionRule.State.TRUE;
        }
        return false;
    }
    void openFromWeb(String itemId) {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(itemId)) {
            fromWeb = true; openItem(i); return;
        }
    }
    private void back() { if (fromWeb) { dismiss(); return; } if (pager != null) folders(); else dismiss(); }
    private void sort() {
        show(new MaterialAlertDialogBuilder(host.requireContext()).setTitle("Sort by")
                .setSingleChoiceItems(new String[]{"Newest first", "Oldest first", "Name A–Z"}, sortOrder,
                        (dialog, which) -> { sortOrder = which; dialog.dismiss(); folders(); }));
    }
    private void search() {
        EditText input = new EditText(host.requireContext()); input.setSingleLine(true); input.setHint("Item name"); input.setText(query);
        new MaterialAlertDialogBuilder(host.requireContext()).setTitle("Search items").setView(input)
                .setPositiveButton("Search", (d, w) -> { query = input.getText().toString().trim(); folders(); })
                .setNeutralButton("Clear", (d, w) -> { query = ""; folders(); }).setNegativeButton("Cancel", null).show();
    }
    private void folders() {
        stopAudio(); pager = null; body.removeAllViews(); heading.setText(libraryTitle);
        sort.setVisibility(View.VISIBLE); search.setVisibility(View.VISIBLE); add.setVisibility(View.VISIBLE); edit.setVisibility(View.GONE);
        searchSummary.setText(query.isEmpty() ? "" : "Results for: “" + query + "”");
        searchSummary.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        shown.clear();
        for (Item p : items) if (p.title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) shown.add(p);
        shown.sort((a, b) -> {
            if (sortOrder == 2) {
                int name = String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title);
                if (name != 0) return name;
            }
            int date = Long.compare(a.submittedAt, b.submittedAt);
            if (date == 0) date = Long.compare(Long.parseLong(a.id), Long.parseLong(b.id));
            return sortOrder == 0 ? -date : date;
        });
        if (shown.isEmpty()) {
            body.addView(text(items.isEmpty() ? "No content saved in this library yet." : "No matching items", 16)); return;
        }
        ScrollView scroll = new ScrollView(host.requireContext()); LinearLayout grid = column(); scroll.addView(grid);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            final int index = i; Item item = shown.get(i);
            if (i % 2 == 0) { row = new LinearLayout(host.requireContext()); grid.addView(row); }
            LinearLayout folder = column(); folder.setPadding(dp(12), dp(16), dp(12), dp(16));
            folder.setGravity(android.view.Gravity.CENTER); folder.setMinimumHeight(dp(144));
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(0xFFFFFFFF); background.setCornerRadius(dp(12)); folder.setBackground(background);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            size.setMargins(i % 2 == 0 ? 0 : dp(6), 0, i % 2 == 0 ? dp(6) : 0, dp(12)); row.addView(folder, size);
            LinearLayout folderTop = new LinearLayout(host.requireContext()); folderTop.setGravity(android.view.Gravity.CENTER_VERTICAL);
            folder.addView(folderTop, new LinearLayout.LayoutParams(-1, -2));
            ImageView image = new ImageView(host.requireContext()); image.setImageResource(R.drawable.icon_file);
            folderTop.addView(image, new LinearLayout.LayoutParams(dp(44), dp(44)));
            folderTop.addView(new View(host.requireContext()), new LinearLayout.LayoutParams(0, 1, 1));
            icon(folderTop, android.R.drawable.ic_menu_edit, "Edit " + item.title, () -> manage(item));
            TextView name = text(item.title, 17); name.setGravity(android.view.Gravity.CENTER); name.setTypeface(null, android.graphics.Typeface.BOLD);
            folder.addView(name);
            folder.setFocusable(true); folder.setContentDescription(item.title);
            folder.setOnClickListener(v -> { if (unlocked()) openItem(index); });
            if (i == shown.size() - 1 && i % 2 == 0) row.addView(new View(host.requireContext()), new LinearLayout.LayoutParams(0, 1, 1));
        }
    }
    private void openItem(int index) {
        body.removeAllViews(); sort.setVisibility(View.GONE); search.setVisibility(View.GONE); add.setVisibility(View.GONE); edit.setVisibility(View.VISIBLE); searchSummary.setVisibility(View.GONE);
        pager = new ViewPager(host.requireContext()); body.addView(pager, new LinearLayout.LayoutParams(-1, -1));
        pager.setAdapter(new PagerAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public boolean isViewFromObject(View view, Object object) { return view == object; }
            @Override public Object instantiateItem(ViewGroup container, int position) {
                ScrollView scroll = new ScrollView(host.requireContext());
                LinearLayout attachments = column(); scroll.addView(attachments);
                Item item = shown.get(position);
                attachment(attachments, item.uri, item.name);
                stamps(attachments, item.context);
                container.addView(scroll); return scroll;
            }
            @Override public void destroyItem(ViewGroup container, int position, Object object) { container.removeView((View) object); }
        });
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) { stopAudio(); heading.setText(shown.get(position).title + "\n" + (position + 1) + " / " + shown.size()); }
        });
        pager.setCurrentItem(index, false); heading.setText(shown.get(index).title + "\n" + (index + 1) + " / " + shown.size());
    }
    private void attachment(LinearLayout list, String value, String name) {
        LinearLayout item = column(); item.setPadding(0, 0, 0, dp(24)); list.addView(item);
        Uri uri = Uri.parse(value); String type;
        try { type = host.requireContext().getContentResolver().getType(uri); } catch (Exception error) { type = null; }
        if (type == null || type.equals("application/octet-stream")) type = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        final String mime = type == null ? "application/octet-stream" : type;
        if (mime.startsWith("image/") || mime.equals("application/pdf")) {
            ImageView preview = new ImageView(host.requireContext()); preview.setAdjustViewBounds(true);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setContentDescription(name);
            item.addView(preview, new LinearLayout.LayoutParams(-1, -2));
            TextView loading = text(mime.equals("application/pdf") ? "Loading first page…" : "Loading photo…", 12); item.addView(loading);
            android.content.Context context = host.requireContext().getApplicationContext();
            previews.execute(() -> {
                Bitmap bitmap = null;
                try {
                    if (mime.equals("application/pdf")) {
                        try (android.os.ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r");
                             android.graphics.pdf.PdfRenderer pdf = new android.graphics.pdf.PdfRenderer(file);
                             android.graphics.pdf.PdfRenderer.Page page = pdf.openPage(0)) {
                            float scale = 720f / Math.max(page.getWidth(), page.getHeight());
                            bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)), Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                            bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        }
                    } else if (android.os.Build.VERSION.SDK_INT >= 28) {
                        bitmap = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
                            float scale = Math.min(1f, 720f / Math.max(info.getSize().getWidth(), info.getSize().getHeight()));
                            decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * scale)), Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                            decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                    } else {
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                        try (java.io.InputStream stream = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, options); }
                        options.inSampleSize = 1;
                        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 720) options.inSampleSize *= 2;
                        options.inJustDecodeBounds = false;
                        try (java.io.InputStream stream = context.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
                    }
                } catch (Exception ignored) {}
                Bitmap result = bitmap;
                main.post(() -> {
                    if (!dialog.isShowing() || !preview.isAttachedToWindow()) { if (result != null) result.recycle(); return; }
                    preview.setImageBitmap(result);
                    loading.setText(result == null ? "Preview unavailable" : mime.equals("application/pdf") ? "First page" : "");
                    if (result != null && !mime.equals("application/pdf")) loading.setVisibility(View.GONE);
                });
            });
        }
        Button open = new Button(host.requireContext()); open.setAllCaps(false);
        if (mime.startsWith("audio/")) {
            open.setText("Play audio"); open.setOnClickListener(v -> play(uri, open));
        } else {
            open.setText("Open " + (mime.startsWith("image/") ? "photo" : "document"));
            open.setOnClickListener(v -> {
                if (!unlocked()) return;
                try { host.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                catch (RuntimeException error) { Toast.makeText(host.requireContext(), "This file is unavailable or no app can open it.", Toast.LENGTH_LONG).show(); }
            });
        }
        item.addView(open);
    }
    private void stamps(LinearLayout list, org.json.JSONObject saved) {
        ConditionSettings settings = new ConditionSettings(host.requireContext(), username);
        if (settings.isEnabled("time")) {
            org.json.JSONObject time = saved.optJSONObject("time");
            String zone = saved.optString("submitted_timezone", time == null ? java.util.TimeZone.getDefault().getID()
                    : time.optString("timezone", "UTC"));
            stamp(list, "Date: " + savedDate(saved.optLong("submitted_at", 0), "MMM d, yyyy", zone));
            stamp(list, "Time: " + savedDate(time == null ? 0 : time.optLong("timestamp", 0), "h:mm a", zone));
        }
        if (settings.isEnabled("battery")) stamp(list, "Battery: " + savedNumber(saved, "battery", "%.0f%%"));
        if (settings.isEnabled("location")) {
            org.json.JSONObject location = saved.optJSONObject("location");
            String value = location == null || location.isNull("latitude") || location.isNull("longitude") ? "N/A"
                    : String.format(Locale.US, "%.5f, %.5f", location.optDouble("latitude"), location.optDouble("longitude"));
            stamp(list, "Location: " + value);
        }
        if (settings.isEnabled("wifi")) stamp(list, "Wi-Fi: " + savedText(saved, "wifi"));
        if (settings.isEnabled("bluetooth")) {
            org.json.JSONObject bluetooth = saved.optJSONObject("bluetooth");
            org.json.JSONArray devices = bluetooth == null ? null : bluetooth.optJSONArray("devices");
            ArrayList<String> names = new ArrayList<>();
            if (devices != null) for (int i = 0; i < devices.length(); i++) {
                org.json.JSONObject device = devices.optJSONObject(i);
                if (device != null) names.add(device.optString("name", device.optString("id", "Device")));
            }
            String value = !names.isEmpty() ? android.text.TextUtils.join(", ", names)
                    : bluetooth != null && bluetooth.optBoolean("known") && devices != null ? "None" : "N/A";
            stamp(list, "Bluetooth: " + value);
        }
        if (settings.isEnabled("history")) stamp(list, "History: " + savedNumber(saved, "history", "%.1f minutes since previous visit"));
        if (settings.isEnabled("environment")) {
            org.json.JSONObject weather = saved.optJSONObject("environment");
            stamp(list, "Weather: " + savedText(weather, "weather"));
            stamp(list, "Temperature: " + savedNumber(weather, "temperature_f", "%.1f°F"));
        }
    }
    private String savedDate(long timestamp, String pattern, String zone) {
        if (timestamp == 0) return "N/A";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern, Locale.getDefault());
        format.setTimeZone(java.util.TimeZone.getTimeZone(zone));
        return format.format(new java.util.Date(timestamp));
    }
    private String savedNumber(org.json.JSONObject saved, String key, String format) {
        double value = saved == null ? Double.NaN : saved.optDouble(key, Double.NaN);
        return Double.isFinite(value) ? String.format(Locale.US, format, value) : "N/A";
    }
    private String savedText(org.json.JSONObject saved, String key) {
        if (saved == null || saved.isNull(key)) return "N/A";
        String value = saved.optString(key).trim();
        return value.isEmpty() ? "None" : value;
    }
    private void stamp(LinearLayout list, String value) {
        TextView label = text(value, 13); label.setPadding(dp(10), dp(6), dp(10), dp(6));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xFFE7EEFF); background.setCornerRadius(dp(10)); label.setBackground(background);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2); size.bottomMargin = dp(6);
        list.addView(label, size);
    }
    private androidx.appcompat.app.AlertDialog show(MaterialAlertDialogBuilder builder) {
        androidx.appcompat.app.AlertDialog child = builder.create();
        childDialogs.add(child); child.setOnDismissListener(d -> childDialogs.remove(child)); child.show(); return child;
    }
    private interface Change { void apply(LibraryStore store); }
    private boolean change(Change action) {
        if (!dialog.isShowing() || !unlocked()) { message("This library is locked."); return false; }
        String viewing = pager == null ? null : shown.get(pager.getCurrentItem()).id;
        try (LibraryStore store = new LibraryStore(host.requireContext())) {
            action.apply(store);
            loadItems(); folders();
            if (viewing != null) for (int i = 0; i < shown.size(); i++) {
                if (viewing.equals(shown.get(i).id)) { openItem(i); break; }
            }
            return true;
        } catch (IllegalArgumentException error) { message(error.getMessage()); }
        catch (Exception error) { message("Could not save changes. Please try again."); }
        return false;
    }
    private void manage(Item item) {
        if (!unlocked()) return;
        stopAudio();
        show(new MaterialAlertDialogBuilder(host.requireContext()).setTitle(item.title)
                .setItems(new String[]{"Rename item", "Delete item"}, (d, which) -> {
                    if (which == 0) rename(item);
                    else show(new MaterialAlertDialogBuilder(host.requireContext()).setTitle("Delete " + item.title + "?")
                            .setMessage("Remove this item from this library? Copies in other libraries and the original file will be kept.")
                            .setNegativeButton("Keep item", null).setPositiveButton("Delete", (confirm, button) ->
                                    change(store -> store.removeItem(username, libraryId, item.id, host.signals))));
                }));
    }
    private void rename(Item item) {
        EditText input = new EditText(host.requireContext()); input.setSingleLine(true); input.setText(item.title); input.selectAll();
        androidx.appcompat.app.AlertDialog editor = show(new MaterialAlertDialogBuilder(host.requireContext()).setTitle("Rename item")
                .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null));
        editor.getButton(-1).setOnClickListener(v -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) { input.setError("Enter an item name."); return; }
            if (change(store -> store.renameItem(username, libraryId, item.id, title, host.signals))) editor.dismiss();
        });
    }
    private void message(String value) { if (host.isAdded()) Toast.makeText(host.requireContext(), value, Toast.LENGTH_LONG).show(); }
    private void play(Uri uri, Button button) {
        if (!unlocked()) return;
        if (playingButton == button && audio != null) { stopAudio(); return; }
        stopAudio(); playingButton = button; button.setText("Loading audio…"); button.setEnabled(false);
        android.media.MediaPlayer player = new android.media.MediaPlayer(); audio = player;
        try {
            player.setDataSource(host.requireContext(), uri);
            player.setOnPreparedListener(p -> {
                if (audio != p || !unlocked()) { stopAudio(); return; }
                p.start(); button.setEnabled(true); button.setText("Stop audio");
            });
            player.setOnCompletionListener(p -> stopAudio());
            player.setOnErrorListener((p, what, extra) -> { stopAudio(); Toast.makeText(host.requireContext(), "Audio unavailable", Toast.LENGTH_SHORT).show(); return true; });
            player.prepareAsync();
        } catch (Exception error) { stopAudio(); Toast.makeText(host.requireContext(), "Audio unavailable", Toast.LENGTH_SHORT).show(); }
    }
    private void stopAudio() {
        if (audio != null) { audio.release(); audio = null; }
        if (playingButton != null) { playingButton.setText("Play audio"); playingButton.setEnabled(true); playingButton = null; }
    }
    private int dp(int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(host.requireContext()); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(String value, int size) { TextView text = new TextView(host.requireContext()); text.setText(value); text.setTextSize(size); text.setTextColor(0xFF252A31); return text; }
    private ImageButton icon(LinearLayout row, int drawable, String description, Runnable action) {
        ImageButton button = new ImageButton(host.requireContext()); button.setImageResource(drawable); button.setContentDescription(description);
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT); button.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48))); button.setOnClickListener(v -> action.run()); return button;
    }
}
