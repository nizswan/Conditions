package com.example.conditions;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.IOException;

public class UploadFragment extends LiveConditionsFragment {
    private PayloadDraft draft;
    private OnBackPressedCallback back;
    private ActivityResultLauncher<String[]> files;
    private ActivityResultLauncher<Uri> camera;

    private void registerPickers() {
        String username = requireArguments().getString("username", "");
        // Stable keys let results return even if Android recreates the activity.
        files = requireActivity().getActivityResultRegistry().register("payload_files_" + username, this,
            new ActivityResultContracts.OpenMultipleDocuments(), selected -> {
                for (Uri uri : selected) {
                    if (draft.uris().contains(uri.toString())) continue;
                    if (draft.uris().size() >= PayloadTypes.LIMIT) {
                        message("You can select up to 10 attachments."); break;
                    }
                    try {
                        String name = fileName(uri);
                        if (!PayloadTypes.allowed(name, requireContext().getContentResolver().getType(uri))) {
                            message("Unsupported file: " + name + ". Videos are not allowed."); continue;
                        }
                        requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        draft.add(uri.toString(), name);
                    } catch (RuntimeException error) { message("Could not open that file. Please choose it again."); }
                }
                renderItems();
            });
        camera = requireActivity().getActivityResultRegistry().register("payload_camera_" + username, this,
            new ActivityResultContracts.TakePicture(), success -> {
                String uri = draft.state.get("camera_uri");
                String name = draft.state.get("camera_name");
                if (uri != null && (!success || !draft.add(uri, name))) deleteCameraPhoto(uri);
                draft.state.set("camera_uri", null);
                draft.state.set("camera_name", null);
                renderItems();
            });

    }

    public UploadFragment() { super(R.layout.fragment_upload); }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        draft = new ViewModelProvider(requireActivity()).get(
                "payload_" + requireArguments().getString("username", ""), PayloadDraft.class);
        registerPickers();
    }
    @Override public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        EditText title = view.findViewById(R.id.payload_title);
        title.setText(draft.title());
        title.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft.state.set("title", s.toString()); title.setError(null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        view.findViewById(R.id.select_payload).setOnClickListener(v -> chooseSource());
        view.findViewById(R.id.upload_select_library).setOnClickListener(v -> {
            if (draft.uris().isEmpty()) { message("Select at least one attachment."); return; }
            if (draft.title().trim().isEmpty()) { title.setError("Enter a payload title."); return; }
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(title.getWindowToken(), 0);
            showStep(true);
        });
        view.findViewById(R.id.payload_back).setOnClickListener(v -> showStep(false));
        view.findViewById(R.id.payload_save).setOnClickListener(v -> savePayload());
        view.findViewById(R.id.payload_add_library).setOnClickListener(v -> new LibraryEditor(this, 0, "", new org.json.JSONObject(), this::showLibraries));
        back = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { showStep(false); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        renderItems();
        showStep(draft.selectingLibrary());
    }
    private void chooseSource() {
        if (draft.uris().size() >= PayloadTypes.LIMIT) { message("You can select up to 10 attachments."); return; }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.select_payload)
                .setItems(new String[]{"Camera (photo)", "Files (select multiple)"}, (dialog, which) -> {
                    if (which == 0) takePhoto();
                    else try { files.launch(PayloadTypes.PICKER_TYPES); }
                    catch (RuntimeException error) { message("No file picker is available."); }
                }).show();
    }
    private void takePhoto() {
        try {
            File folder = new File(requireContext().getFilesDir(), "payload_camera");
            if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create photo folder");
            File photo = File.createTempFile("Photo_", ".jpg", folder);
            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".payloads", photo);
            draft.state.set("camera_uri", uri.toString());
            draft.state.set("camera_name", photo.getName());
            camera.launch(uri);
        } catch (IOException | RuntimeException error) {
            String uri = draft.state.get("camera_uri");
            if (uri != null) deleteCameraPhoto(uri);
            draft.state.set("camera_uri", null);
            draft.state.set("camera_name", null);
            message("Could not open the camera.");
        }
    }
    private String fileName(Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        return "Attachment";
    }
    private void renderItems() {
        if (getView() == null) return;
        ((TextView) getView().findViewById(R.id.payload_count)).setText(getString(R.string.payload_count, draft.uris().size()));
        LinearLayout items = getView().findViewById(R.id.payload_items);
        items.removeAllViews();
        for (int i = 0; i < draft.uris().size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(requireContext());
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView name = new TextView(requireContext());
            name.setText(draft.names().get(i));
            name.setTextColor(android.graphics.Color.rgb(23, 25, 29));
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton remove = new MaterialButton(requireContext());
            remove.setText(R.string.payload_remove);
            remove.setTextSize(12);
            remove.setMinWidth(0);
            remove.setMinimumWidth(0);
            int padding = Math.round(12 * getResources().getDisplayMetrics().density);
            remove.setPadding(padding, 0, padding, 0);
            remove.setInsetTop(Math.round(6 * getResources().getDisplayMetrics().density));
            remove.setInsetBottom(Math.round(6 * getResources().getDisplayMetrics().density));
            remove.setContentDescription(getString(R.string.payload_remove_named, draft.names().get(i)));
            remove.setOnClickListener(v -> {
                deleteCameraPhoto(draft.uris().get(index));
                draft.remove(index);
                renderItems();
            });
            row.addView(remove);
            items.addView(row);
        }
    }
    private void deleteCameraPhoto(String value) {
        Uri uri = Uri.parse(value);
        if ((requireContext().getPackageName() + ".payloads").equals(uri.getAuthority())) {
            try { requireContext().getContentResolver().delete(uri, null, null); }
            catch (RuntimeException ignored) {}
        }
    }
    private void showStep(boolean libraries) {
        draft.state.set("selecting", libraries);
        if (getView() == null) return;
        getView().findViewById(R.id.payload_editor).setVisibility(libraries ? View.GONE : View.VISIBLE);
        getView().findViewById(R.id.payload_libraries).setVisibility(libraries ? View.VISIBLE : View.GONE);
        back.setEnabled(libraries);
        setConditionsActive(true);
        if (libraries) showLibraries();
        ((ScrollView) getView()).smoothScrollTo(0, 0);
    }
    @Override protected void readingsChanged() {
        if (getView() != null && draft != null && draft.selectingLibrary()) showLibraries();
    }
    private void showLibraries() {
        ((TextView) getView().findViewById(R.id.payload_summary)).setText(getString(R.string.payload_summary, draft.title(), draft.uris().size()));
        LinearLayout options = getView().findViewById(R.id.payload_library_options);
        options.removeAllViews();
        java.util.ArrayList<Long> available = new java.util.ArrayList<>();
        try (LibraryStore store = new LibraryStore(requireContext());
             Cursor libraries = store.forUser(requireArguments().getString("username", ""))) {
            getView().findViewById(R.id.payload_no_libraries).setVisibility(libraries.getCount() == 0 ? View.VISIBLE : View.GONE);
            while (libraries.moveToNext()) {
                long id = libraries.getLong(0);
                CheckBox option = new CheckBox(requireContext());
                option.setId(View.generateViewId());
                ConditionRule.State status = ConditionRule.evaluate(ConditionRule.read(libraries.getString(2)), signals);
                boolean unlocked = status == ConditionRule.State.TRUE;
                if (unlocked || signals.updated == 0) available.add(id);
                option.setText(libraries.getString(1) + (unlocked ? " (Unlocked)" : " (Locked)"));
                option.setEnabled(unlocked);
                options.addView(option);
                option.setChecked(unlocked && draft.libraries().contains(id));
                option.setOnCheckedChangeListener((button, checked) -> draft.selectLibrary(id, checked));
            }
        }
        for (long id : new java.util.ArrayList<>(draft.libraries()))
            if (!available.contains(id)) draft.selectLibrary(id, false);
    }
    private void savePayload() {
        View save = getView().findViewById(R.id.payload_save);
        save.setEnabled(false);
        try (LibraryStore store = new LibraryStore(requireContext())) {
            for (String value : draft.uris()) {
                try (java.io.InputStream input = requireContext().getContentResolver().openInputStream(Uri.parse(value))) {
                    if (input == null) throw new IOException("Attachment unavailable");
                }
            }
            refreshReadings();
            int count = draft.libraries().size();
            store.addItems(requireArguments().getString("username", ""), draft, signals);
            draft.clear();
            ((EditText) getView().findViewById(R.id.payload_title)).setText("");
            renderItems();
            showStep(false);
            message("Added to " + count + (count == 1 ? " library." : " libraries."));
        } catch (IllegalArgumentException error) {
            message(error.getMessage());
            showLibraries();
        } catch (Exception error) {
            message("Could not save. Check that the attachments are still available and try again.");
        } finally { save.setEnabled(true); }
    }
    private void message(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show();
    }
}
