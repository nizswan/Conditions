package com.example.conditions;

import androidx.fragment.app.Fragment;
import android.os.Handler;
import android.os.Looper;
import android.os.BatteryManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

public class LiveConditionsFragment extends Fragment {

    protected final RuleSignals signals = new RuleSignals();
    protected void readingsChanged() {}
    private GpsCondition gps;
    private BluetoothCondition bluetooth;
    private WeatherCondition weather;
    private LibraryCover.Selected coverSelected;
    private final ActivityResultLauncher<String> coverPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                LibraryCover.Selected selected = coverSelected;
                coverSelected = null;
                if (uri == null || selected == null || getView() == null) return;
                try {
                    selected.accept(LibraryCover.read(requireContext(), uri));
                } catch (Exception error) {
                    Toast.makeText(requireContext(), "Could not open that photo.", Toast.LENGTH_LONG).show();
                }
            });

    void chooseCover(LibraryCover.Selected selected) {
        coverSelected = selected;
        try {
            coverPicker.launch("image/*");
        } catch (RuntimeException error) {
            coverSelected = null;
            Toast.makeText(requireContext(), "No photo picker is available.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onDestroyView() {
        coverSelected = null;
        super.onDestroyView();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            View view = getView();
            if (view == null) return;
            ConditionSettings settings = new ConditionSettings(requireContext(),
                    requireArguments().getString("username", ""));
            boolean time = settings.isEnabled("time");
            boolean location = settings.isEnabled("location");
            boolean battery = settings.isEnabled("battery");
            boolean bluetoothEnabled = settings.isEnabled("bluetooth");
            boolean wifi = settings.isEnabled("wifi");
            boolean history = settings.isEnabled("history");
            boolean environment = settings.isEnabled("environment");
            view.findViewById(R.id.live_conditions_card).setVisibility(
                    time || location || battery || bluetoothEnabled || wifi || history || environment ? View.VISIBLE : View.GONE);
            view.findViewById(R.id.live_time).setVisibility(time ? View.VISIBLE : View.GONE);
            TextView coordinates = view.findViewById(R.id.live_location);
            coordinates.setVisibility(location ? View.VISIBLE : View.GONE);
            String locationStatus = null;
            if (location || environment) locationStatus = gps.getText();
            else gps.stop();
            if (location) coordinates.setText(getString(R.string.live_location_label, locationStatus));
            TextView environmentText = view.findViewById(R.id.live_environment);
            environmentText.setVisibility(environment ? View.VISIBLE : View.GONE);
            if (environment) {
                if (weather == null) weather = new WeatherCondition();
                environmentText.setText(getString(R.string.live_environment_label,
                        gps.getLocation() == null ? locationStatus : weather.getText(gps.getLocation())));
            } else if (weather != null) {
                weather.close();
                weather = null;
            }
            TextView batteryLevel = view.findViewById(R.id.live_battery);
            batteryLevel.setVisibility(battery ? View.VISIBLE : View.GONE);
            signals.battery = null;
            if (battery) {
                BatteryManager manager = requireContext().getSystemService(BatteryManager.class);
                int percent = manager == null ? -1
                        : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                boolean available = percent >= 0 && percent <= 100;
                if (available) signals.battery = (double) percent;
                batteryLevel.setText(available
                        ? getString(R.string.battery_percent, percent) : getString(R.string.battery_unavailable));
            }
            TextView bluetoothNames = view.findViewById(R.id.live_bluetooth);
            bluetoothNames.setVisibility(bluetoothEnabled ? View.VISIBLE : View.GONE);
            if (bluetoothEnabled) bluetoothNames.setText(getString(R.string.live_bluetooth_label, bluetooth.getText()));
            else bluetooth.stop();
            TextView wifiName = view.findViewById(R.id.live_wifi);
            wifiName.setVisibility(wifi ? View.VISIBLE : View.GONE);
            if (wifi) wifiName.setText(getString(R.string.live_wifi_label, WifiCondition.getName(requireContext())));
            TextView lastVisit = view.findViewById(R.id.live_history);
            lastVisit.setVisibility(history ? View.VISIBLE : View.GONE);
            if (history) lastVisit.setText(getString(R.string.history_last_visit,
                    ago(((MainActivity) requireActivity()).previousVisit(), System.currentTimeMillis())));
            signals.settings = settings;
            signals.location = location ? gps.getLocation() : null;
            signals.devices.clear();
            if (bluetoothEnabled) signals.devices.putAll(bluetooth.currentDevices);
            signals.bluetoothKnown = bluetoothEnabled && bluetooth.known;
            signals.wifi = wifi ? WifiCondition.currentSsid(requireContext()) : null;
            long previous = ((MainActivity) requireActivity()).previousVisit();
            signals.history = history && previous > 0 ? Math.max(0, System.currentTimeMillis() - previous) / 60000.0 : null;
            boolean weatherReady = environment && weather != null && weather.hasReading(gps.getLocation());
            signals.temperature = weatherReady ? weather.temperature : null;
            signals.weather = weatherReady ? weather.category : null;
            signals.updated = android.os.SystemClock.elapsedRealtime();
            readingsChanged();
            // Refresh while this screen is visible so condition values stay current.
            handler.postDelayed(this, 3000);
        }
    };

    private boolean conditionsActive = true;

    protected LiveConditionsFragment(int layout) {
        super(layout);
    }

    protected void setConditionsActive(boolean active) {
        conditionsActive = active;
        stopConditions();
        if (active && isResumed()) startConditions();
    }

    @Override public void onViewCreated(@androidx.annotation.NonNull View view,
                                       @androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View.OnClickListener locationHelp = button ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.permission_location)
                        .setMessage(R.string.gps_help)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
        view.findViewById(R.id.live_location).setOnClickListener(locationHelp);
        view.findViewById(R.id.live_environment).setOnClickListener(button -> {
            if (weather == null || gps.getLocation() == null) {
                locationHelp.onClick(button);
                return;
            }
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.permission_environment)
                    .setMessage(weather.getDetails())
                    .setPositiveButton(R.string.weather_refresh, (dialog, which) -> {
                        if (weather == null) return;
                        weather.refreshNow();
                        handler.removeCallbacks(refresh);
                        handler.post(refresh);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    @Override public void onResume() {
        super.onResume();
        if (conditionsActive) startConditions();
    }

    protected void refreshReadings() {
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    private void startConditions() {
        gps = new GpsCondition(requireContext());
        bluetooth = new BluetoothCondition(requireContext());
        handler.post(refresh);
    }

    @Override public void onPause() {
        stopConditions();
        super.onPause();
    }

    private void stopConditions() {
        signals.updated = 0;
        handler.removeCallbacks(refresh);
        if (gps != null) gps.stop();
        if (bluetooth != null) bluetooth.stop();
        if (weather != null) weather.close();
        weather = null;
    }


    static String ago(long previousVisit, long now) {
        if (previousVisit == 0) return "First Visit";
        long minutes = Math.max(0, now - previousVisit) / 60000;
        if (minutes < 1) return "<1 min ago";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }
}
