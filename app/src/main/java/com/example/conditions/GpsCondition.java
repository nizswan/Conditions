package com.example.conditions;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.util.Locale;

final class GpsCondition implements LocationListener {
    private final Context context;
    private final LocationManager manager;
    private Location location;
    private boolean listening;
    private CancellationSignal currentRequest;
    private long requestedAt;

    GpsCondition(Context context) {
        this.context = context;
        manager = context.getSystemService(LocationManager.class);
    }

    String getText() {
        if (!WifiCondition.hasPermission(context)) {
            stop();
            return context.getString(R.string.gps_permission_needed);
        }
        try {
            if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                stop();
                return context.getString(R.string.gps_disabled);
            }
            if (!listening) {
                // Reuse a recent GPS fix instead of starting empty on every tab change.
                location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                requestedAt = SystemClock.elapsedRealtime();
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
                listening = true;
                if (Build.VERSION.SDK_INT >= 30) {
                    currentRequest = new CancellationSignal();
                    manager.getCurrentLocation(LocationManager.GPS_PROVIDER, currentRequest,
                            context.getMainExecutor(), fix -> {
                                if (listening && fix != null) location = fix;
                            });
                }
            }
            // Do not present an old fix as the current position.
            if (location == null || SystemClock.elapsedRealtimeNanos()
                    - location.getElapsedRealtimeNanos() > 120_000_000_000L) {
                return context.getString(SystemClock.elapsedRealtime() - requestedAt < 30000
                        ? R.string.gps_waiting : R.string.gps_no_fix);
            }
            return String.format(Locale.US, "%.4f, %.4f",
                    location.getLatitude(), location.getLongitude());
        } catch (SecurityException error) {
            stop();
            return context.getString(R.string.gps_permission_needed);
        }
    }

    Location getLocation() {
        if (location == null || SystemClock.elapsedRealtimeNanos()
                - location.getElapsedRealtimeNanos() > 120_000_000_000L) return null;
        return new Location(location);
    }

    void stop() {
        if (currentRequest != null) currentRequest.cancel();
        currentRequest = null;
        if (listening && manager != null) manager.removeUpdates(this);
        listening = false;
        location = null;
    }

    @Override public void onLocationChanged(Location location) {
        this.location = location;
    }

    @Override public void onProviderDisabled(String provider) { location = null; }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
}
