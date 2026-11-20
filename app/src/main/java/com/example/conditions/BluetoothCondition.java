package com.example.conditions;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class BluetoothCondition {
    private final Context context;
    private final BluetoothManager manager;
    private final BluetoothAdapter adapter;
    private final Map<Integer, BluetoothProfile> profiles = new HashMap<>();
    private final Set<Integer> pending = new HashSet<>();
    final Map<String, String> currentDevices = new HashMap<>();
    boolean known;
    private boolean started;
    private long startedAt;

    BluetoothCondition(Context context) {
        this.context = context;
        manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (!started) {
                adapter.closeProfileProxy(profile, proxy);
                return;
            }
            profiles.put(profile, proxy);
            pending.remove(profile);
        }

        @Override public void onServiceDisconnected(int profile) {
            profiles.remove(profile);
            pending.remove(profile);
        }
    };

    private void requestProfile(int profile) {
        pending.add(profile);
        if (!adapter.getProfileProxy(context, listener, profile)) pending.remove(profile);
    }

    String getText() {
        currentDevices.clear();
        known = false;
        if (!hasPermission(context)) {
            stop();
            return context.getString(R.string.bluetooth_permission_needed);
        }
        try {
            if (adapter == null || !adapter.isEnabled()) {
                stop();
                known = true;
                return context.getString(R.string.bluetooth_none);
            }
            // Android 17 checks connections directly without waiting for optional profiles.
            if (!started && Build.VERSION.SDK_INT < 37) {
                started = true;
                startedAt = android.os.SystemClock.elapsedRealtime();
                requestProfile(BluetoothProfile.HEADSET);
                requestProfile(BluetoothProfile.A2DP);
                if (Build.VERSION.SDK_INT >= 28) requestProfile(BluetoothProfile.HEARING_AID);
                if (Build.VERSION.SDK_INT >= 33) requestProfile(BluetoothProfile.LE_AUDIO);
            }
            // Query current connections, not just the saved list of paired devices.
            Set<BluetoothDevice> connected = new HashSet<>();
            for (BluetoothProfile profile : profiles.values()) connected.addAll(profile.getConnectedDevices());
            connected.addAll(manager.getConnectedDevices(BluetoothProfile.GATT));
            connected.addAll(manager.getConnectedDevices(BluetoothProfile.GATT_SERVER));
            if (Build.VERSION.SDK_INT >= 37) {
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    if (device.isConnected(BluetoothDevice.TRANSPORT_BREDR)
                            || device.isConnected(BluetoothDevice.TRANSPORT_LE)) connected.add(device);
                }
            }
            ArrayList<String> names = new ArrayList<>();
            for (BluetoothDevice device : connected) {
                String name = device.getName();
                currentDevices.put(device.getAddress(), name == null ? "Unnamed device" : name);
                names.add(name == null || name.trim().isEmpty()
                        ? context.getString(R.string.bluetooth_unnamed) : name);
            }
            java.util.Collections.sort(names);
            if (!names.isEmpty()) { known = pending.isEmpty() || Build.VERSION.SDK_INT >= 37; return TextUtils.join(", ", names); }
            boolean waitingForConnectedProfile = false;
            for (int profile : pending) {
                if (adapter.getProfileConnectionState(profile) == BluetoothAdapter.STATE_CONNECTED) {
                    waitingForConnectedProfile = true;
                }
            }
            if (waitingForConnectedProfile && android.os.SystemClock.elapsedRealtime() - startedAt > 10000) {
                return context.getString(R.string.bluetooth_unavailable);
            }
            known = !waitingForConnectedProfile && pending.isEmpty();
            return context.getString(waitingForConnectedProfile
                    ? R.string.bluetooth_checking : R.string.bluetooth_none);
        } catch (SecurityException error) {
            stop();
            return context.getString(R.string.bluetooth_permission_needed);
        }
    }

    void stop() {
        started = false;
        for (Map.Entry<Integer, BluetoothProfile> entry : profiles.entrySet()) {
            adapter.closeProfileProxy(entry.getKey(), entry.getValue());
        }
        profiles.clear();
        pending.clear();
    }
}
