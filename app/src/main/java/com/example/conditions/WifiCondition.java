package com.example.conditions;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import androidx.core.location.LocationManagerCompat;

final class WifiCondition {
    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static String currentSsid(Context context) {
        if (!hasPermission(context)) return null;
        LocationManager location = context.getSystemService(LocationManager.class);
        if (location == null || !LocationManagerCompat.isLocationEnabled(location)) return null;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            if (!wifi.isWifiEnabled()) return "";
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return "";
            String ssid = info.getSSID();
            if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) return null;
            return ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
        } catch (SecurityException error) { return null; }
    }

    // Read only the connected network name. No scanning or location coordinates.
    @SuppressWarnings("deprecation")
    static String getName(Context context) {
        if (!hasPermission(context)) return context.getString(R.string.wifi_permission_needed);
        LocationManager location = context.getSystemService(LocationManager.class);
        if (location != null && !LocationManagerCompat.isLocationEnabled(location)) {
            return context.getString(R.string.wifi_location_needed);
        }
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null || !wifi.isWifiEnabled()) {
                return context.getString(R.string.wifi_disconnected);
            }
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) {
                return context.getString(R.string.wifi_disconnected);
            }
            String name = info.getSSID();
            if (name == null || name.isEmpty() || WifiManager.UNKNOWN_SSID.equals(name)) {
                return context.getString(R.string.wifi_unavailable);
            }
            if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
                name = name.substring(1, name.length() - 1);
            }
            return name;
        } catch (SecurityException error) {
            return context.getString(R.string.wifi_permission_needed);
        }
    }
}
