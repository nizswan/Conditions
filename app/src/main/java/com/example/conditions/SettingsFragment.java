package com.example.conditions;

import androidx.fragment.app.Fragment;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class SettingsFragment extends Fragment {

    private final androidx.activity.result.ActivityResultLauncher<String> bluetoothPermission =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (isAdded() && !granted) {
                            android.widget.Toast.makeText(requireContext(), R.string.bluetooth_permission_needed,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });

    private final androidx.activity.result.ActivityResultLauncher<String[]> locationPermission =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (isAdded() && !WifiCondition.hasPermission(requireContext())) {
                            android.widget.Toast.makeText(requireContext(),
                                    R.string.precise_permission_needed, android.widget.Toast.LENGTH_LONG).show();
                        }
                    });

    public SettingsFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView credit = view.findViewById(R.id.weather_credit);
        credit.setText(android.text.Html.fromHtml(getString(R.string.weather_credit), android.text.Html.FROM_HTML_MODE_LEGACY));
        credit.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        view.findViewById(R.id.sign_out).setOnClickListener(button -> {
            button.setEnabled(false);
            ((MainActivity) requireActivity()).signOut();
        });
        view.findViewById(R.id.reset_libraries).setOnClickListener(button -> confirmResetLibraries());
        view.findViewById(R.id.edit_name).setOnClickListener(button -> editProfile("name"));
        view.findViewById(R.id.delete_account).setOnClickListener(button -> confirmDeleteAccount());
        view.findViewById(R.id.edit_username).setOnClickListener(button -> editProfile("username"));
        Bundle user = getArguments();
        ConditionSettings settings = new ConditionSettings(requireContext(),
                user == null ? "" : user.getString("username", ""));
        int[] switches = {R.id.permission_time, R.id.permission_location,
                R.id.permission_battery, R.id.permission_bluetooth,
                R.id.permission_wifi, R.id.permission_history, R.id.permission_environment};
        String[] conditions = {"time", "location", "battery", "bluetooth", "wifi", "history", "environment"};
        for (int i = 0; i < switches.length; i++) {
            String condition = conditions[i];
            com.google.android.material.switchmaterial.SwitchMaterial toggle =
                    view.findViewById(switches[i]);
            toggle.setChecked(settings.isEnabled(condition));
            toggle.setOnCheckedChangeListener((button, enabled) -> {
                settings.setEnabled(condition, enabled);
                if (condition.equals("history")) ((MainActivity) requireActivity()).updateHistory();
                if (condition.equals("bluetooth") && enabled
                        && !BluetoothCondition.hasPermission(requireContext())) {
                    bluetoothPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT);
                }
                if ((condition.equals("wifi") || condition.equals("location") || condition.equals("environment")) && enabled
                        && !WifiCondition.hasPermission(requireContext())) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(condition.equals("environment") ? R.string.permission_environment
                                    : condition.equals("wifi") ? R.string.permission_wifi : R.string.permission_location)
                            .setMessage(condition.equals("environment") ? R.string.environment_permission_reason : condition.equals("wifi")
                                    ? R.string.wifi_permission_reason : R.string.gps_permission_reason)
                            .setPositiveButton(R.string.continue_permission, (dialog, which) ->
                                    locationPermission.launch(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION}))
                            .setNegativeButton(android.R.string.cancel, (dialog, which) -> toggle.setChecked(false))
                            .setOnCancelListener(dialog -> toggle.setChecked(false))
                            .show();
                }
            });
        }
        ((TextView) view.findViewById(R.id.settings_name)).setText(
                getString(R.string.settings_name, user == null ? "" : user.getString("name", "")));
        ((TextView) view.findViewById(R.id.settings_username)).setText(
                getString(R.string.settings_username,
                        user == null ? "" : user.getString("username", "")));
        long loggedOn = user == null ? 0 : user.getLong("logged_on");
        SimpleDateFormat format = new SimpleDateFormat("MM/dd/yy h:mm a", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        ((TextView) view.findViewById(R.id.last_login)).setText(
                getString(R.string.last_login,
                        loggedOn == 0 ? "—" : format.format(new Date(loggedOn))));
        view.findViewById(R.id.last_login).setVisibility(loggedOn == 0 ? View.GONE : View.VISIBLE);
    }
    private void confirmResetLibraries() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset libraries?")
                .setMessage("Delete all your libraries and their items on this device? This cannot be undone. Your account, settings, and original files will be kept.")
                .setNegativeButton("Keep libraries", null)
                .setPositiveButton("Reset libraries", (dialog, which) -> {
                    String username = requireArguments().getString("username", "");
                    try (LibraryStore store = new LibraryStore(requireContext())) {
                        store.resetLibraries(username);
                        PayloadDraft draft = new androidx.lifecycle.ViewModelProvider(requireActivity())
                                .get("payload_" + username, PayloadDraft.class);
                        draft.state.set("library_ids", new java.util.ArrayList<Long>());
                        draft.state.set("library_id", null);
                        android.widget.Toast.makeText(requireContext(), "Libraries reset", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }
    private void confirmDeleteAccount() {
        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete account?")
                .setMessage("Permanently delete your account? Your libraries, cover photos, saved rules, choices, and history on this device will be removed. This cannot be undone.")
                .setNegativeButton("Keep account", null).setPositiveButton("Delete account", null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(-1).setOnClickListener(button -> {
            dialog.getButton(-1).setEnabled(false);
            dialog.getButton(-2).setEnabled(false);
            dialog.setCancelable(false);
            ((MainActivity) requireActivity()).deleteAccount((user, error) -> {
                if (!isAdded()) return;
                if (error == null) {
                    dialog.dismiss();
                    android.widget.Toast.makeText(requireContext(), "Account deleted", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    dialog.setMessage(error);
                    dialog.getButton(-1).setEnabled(true);
                    dialog.getButton(-2).setEnabled(true);
                    dialog.setCancelable(true);
                }
            });
        }));
        dialog.show();
    }
    private void editProfile(String field) {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | (field.equals("name") ? android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                : android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        input.setText(requireArguments().getString(field, ""));
        input.selectAll();
        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(field.equals("name") ? "Edit name" : "Edit username")
                .setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm", null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(-1).setOnClickListener(button -> {
            String value = input.getText().toString().trim();
            if (field.equals("name") ? value.isEmpty() || value.length() > 100
                    : !value.matches("[A-Za-z0-9]{1,32}")) {
                input.setError(field.equals("name") ? "Enter a name (up to 100 characters)."
                        : "Use 1-32 letters or numbers.");
                return;
            }
            dialog.getButton(-1).setEnabled(false);
            dialog.getButton(-2).setEnabled(false);
            dialog.setCancelable(false);
            ((MainActivity) requireActivity()).updateProfile(field, value, (user, error) -> {
                if (!isAdded()) return;
                if (error != null) {
                    input.setError(error);
                    dialog.getButton(-1).setEnabled(true);
                    dialog.getButton(-2).setEnabled(true);
                    dialog.setCancelable(true);
                } else {
                    dialog.dismiss();
                    android.widget.Toast.makeText(requireContext(), "Account updated", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }));
        dialog.show();
    }
}
