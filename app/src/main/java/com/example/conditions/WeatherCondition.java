package com.example.conditions;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WeatherCondition {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Location requestedLocation;
    private long requestedAt;
    private long weatherTime;
    private String weather;
    private boolean loading;
    private boolean closed;
    private boolean failed;
    Double temperature;
    String category;

    boolean hasReading(Location location) {
        return location != null && requestedLocation != null && weather != null
                && location.distanceTo(requestedLocation) <= 5000
                && System.currentTimeMillis() - weatherTime <= 3600000;
    }

    void refreshNow() {
        if (!loading) {
            requestedLocation = null;
            weather = null;
            weatherTime = 0;
        }
    }

    String getDetails() {
        if (requestedLocation == null) return "Weather needs a current device location.";
        String position = String.format(Locale.US, "Weather location: %.4f, %.4f",
                requestedLocation.getLatitude(), requestedLocation.getLongitude());
        String updated = weatherTime == 0 ? "No weather received yet"
                : "Weather data time: " + java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(weatherTime));
        String emulator = android.os.Build.HARDWARE.equals("ranchu")
                || android.os.Build.HARDWARE.equals("goldfish")
                ? "\n\nThis emulator uses supplied coordinates. With the Windows location helper running, these follow the computer's approximate location; otherwise they come from the emulator controls."
                : "";
        return position + "\n" + updated
                + "\n\nOpen-Meteo model data. Temperature is in Fahrenheit."
                + emulator;
    }

    String getText(Location location) {
        if (location == null) return "Weather needs a current location";
        long now = SystemClock.elapsedRealtime();
        boolean moved = requestedLocation != null && location.distanceTo(requestedLocation) > 5000;
        long refreshAfter = failed || moved ? 60000 : 15 * 60000;
        if (!closed && !loading && (requestedLocation == null || now - requestedAt >= refreshAfter)) {
            requestedLocation = new Location(location);
            requestedAt = now;
            weather = null;
            loading = true;
            fetch(requestedLocation);
        }
        // Never display weather from a previous area or an old response as current.
        if (weather != null && !moved && System.currentTimeMillis() - weatherTime <= 3600000) return weather;
        return loading ? "Loading weather…" : "Weather unavailable";
    }

    private void fetch(Location location) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            String text = null;
            long time = 0;
            Double degrees = null;
            String label = null;
            try {
                String address = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day"
                                + "&temperature_unit=fahrenheit&timeformat=unixtime&forecast_days=1",
                        location.getLatitude(), location.getLongitude());
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setUseCaches(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather unavailable");
                StringBuilder json = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) json.append(line);
                }
                JSONObject current = new JSONObject(json.toString()).getJSONObject("current");
                double temperature = current.getDouble("temperature_2m");
                if (!Double.isFinite(temperature)) throw new java.io.IOException("Missing temperature");
                time = current.getLong("time") * 1000;
                long age = System.currentTimeMillis() - time;
                if (age > 3600000 || age < -15 * 60000) throw new java.io.IOException("Outdated weather");
                degrees = temperature;
                label = fromCode(current.getInt("weather_code"), current.getInt("is_day") == 1);
                text = String.format(Locale.US, "%.1f°F %s", temperature, label);
            } catch (Exception error) {
                // No made-up temperature or weather if the request fails.
            } finally {
                if (connection != null) connection.disconnect();
            }
            String result = text;
            long resultTime = time;
            Double resultDegrees = degrees;
            String resultLabel = label;
            main.post(() -> {
                if (closed) return;
                weather = result;
                temperature = resultDegrees;
                category = resultLabel;
                weatherTime = resultTime;
                failed = result == null;
                loading = false;
            });
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    // Open-Meteo uses WMO weather codes.
    static String fromCode(int code, boolean day) {
        switch (code) {
            case 0: case 1: return day ? "Sunny" : "Clear";
            case 2: case 3: return "Cloudy";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return "Rainy";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Snowy";
            case 95: case 96: case 97: case 99: return "Thunderstorms";
            default: return "Unknown weather";
        }
    }
}
