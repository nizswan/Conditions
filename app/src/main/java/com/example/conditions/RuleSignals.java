package com.example.conditions;

import android.location.Location;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

final class RuleSignals implements ConditionRule.Reading {
    ConditionSettings settings;
    Location location;
    Double battery, temperature, history;
    String wifi, weather;
    boolean bluetoothKnown;
    long updated;
    final Map<String, String> devices = new LinkedHashMap<>();

    // Preserve the submission readings; later edits do not replace this snapshot.
    JSONObject snapshot() {
        JSONObject saved = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            saved.put("submitted_at", now);
            saved.put("submitted_timezone", java.util.TimeZone.getDefault().getID());
            boolean fresh = updated != 0 && android.os.SystemClock.elapsedRealtime() - updated <= 10000;
            for (String type : new String[]{"time", "location", "battery", "bluetooth", "wifi", "history", "environment"}) {
                if (settings == null || !settings.isEnabled(type)) continue;
                Object value = JSONObject.NULL;
                if (type.equals("time")) {
                    value = new JSONObject().put("timestamp", now).put("timezone", java.util.TimeZone.getDefault().getID());
                } else if (fresh) {
                    switch (type) {
                        case "location":
                            if (location != null) value = new JSONObject().put("latitude", location.getLatitude())
                                    .put("longitude", location.getLongitude()).put("measured_at", location.getTime())
                                    .put("accuracy_metres", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
                            break;
                        case "battery": if (battery != null) value = battery; break;
                        case "wifi": if (wifi != null) value = wifi; break;
                        case "history": if (history != null) value = history; break;
                        case "environment":
                            value = new JSONObject().put("temperature_f", temperature == null ? JSONObject.NULL : temperature)
                                    .put("weather", weather == null ? JSONObject.NULL : weather);
                            break;
                        case "bluetooth":
                            org.json.JSONArray connected = new org.json.JSONArray();
                            for (Map.Entry<String, String> device : devices.entrySet())
                                connected.put(new JSONObject().put("id", device.getKey()).put("name", device.getValue()));
                            value = new JSONObject().put("known", bluetoothKnown).put("devices", connected);
                            break;
                    }
                }
                saved.put(type, value);
            }
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("Could not save submission conditions.", error); }
        return saved;
    }

    @Override public ConditionRule.State evaluate(JSONObject piece) {
        String type = piece.optString("type");
        String setting = type.equals("temperature") || type.equals("weather") ? "environment" : type;
        if (settings == null || !settings.isEnabled(setting) || updated == 0
                || android.os.SystemClock.elapsedRealtime() - updated > 10000) return ConditionRule.State.UNKNOWN;
        Boolean matches = null;
        double min = piece.optDouble("min"), max = piece.optDouble("max");
        switch (type) {
            case "time":
                Calendar now = Calendar.getInstance();
                int minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
                matches = min <= max ? minutes >= min && minutes <= max : minutes >= min || minutes <= max;
                break;
            case "battery": matches = range(battery, min, max); break;
            case "temperature": matches = range(temperature, min, max); break;
            case "history": matches = range(history, min, max); break;
            case "wifi": if (wifi != null) matches = wifi.equals(piece.optString("value")); break;
            case "bluetooth":
                if (piece.optString("value").equals("none")) {
                    if (!devices.isEmpty()) matches = false;
                    else if (bluetoothKnown) matches = true;
                }
                else if (devices.containsKey(piece.optString("value"))) matches = true;
                else if (bluetoothKnown) matches = false;
                break;
            case "weather": if (weather != null && !weather.equals("Unknown weather")) matches = weather.equals(piece.optString("value")); break;
            case "location":
                if (location != null && location.hasAccuracy()) {
                    float[] distance = new float[1];
                    Location.distanceBetween(location.getLatitude(), location.getLongitude(), piece.optDouble("lat"), piece.optDouble("lon"), distance);
                    double radius = piece.optDouble("radius");
                    // An accuracy circle crossing the boundary cannot reliably establish inside or outside.
                    if (distance[0] + location.getAccuracy() <= radius) matches = true;
                    else if (distance[0] - location.getAccuracy() > radius) matches = false;
                }
                break;
        }
        return matches == null ? ConditionRule.State.UNKNOWN : matches ? ConditionRule.State.TRUE : ConditionRule.State.FALSE;
    }
    private Boolean range(Double value, double min, double max) {
        return value == null ? null : value >= min && value <= max;
    }
}
