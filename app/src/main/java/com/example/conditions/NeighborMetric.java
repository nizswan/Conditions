package com.example.conditions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

final class NeighborMetric {
    static class Edge {
        final String a, b;
        Edge(String a, String b) { this.a = a; this.b = b; }
    }
    final double[] weights, min, max;
    final boolean[] enabled;
    NeighborMetric(ArrayList<ContextWebFragment.Item> library, double[] selected) {
        weights = selected.clone(); min = new double[weights.length]; max = new double[weights.length];
        enabled = new boolean[weights.length];
        for (int i = 0; i < weights.length; i++) {
            enabled[i] = selected[i] > 0;
            if (i == 5 || i == 6 || i == 7) weights[i] = 0;
        }
        double largest = 0, total = 0;
        for (double weight : weights) largest = Math.max(largest, weight);
        if (largest > 0) {
            for (int i = 0; i < weights.length; i++) { weights[i] /= largest; total += weights[i]; }
            for (int i = 0; i < weights.length; i++) weights[i] /= total;
        }
        java.util.Arrays.fill(min, Double.POSITIVE_INFINITY); java.util.Arrays.fill(max, Double.NEGATIVE_INFINITY);
        for (ContextWebFragment.Item item : library) for (int i = 0; i < weights.length; i++) {
            Object value = item.value(i);
            if (value instanceof Number) { double n = ((Number) value).doubleValue(); min[i] = Math.min(min[i], n); max[i] = Math.max(max[i], n); }
        }
    }
    boolean available(ContextWebFragment.Item item) {
        for (int i = 0; i < weights.length; i++) if (enabled[i] && item.value(i) == null) return false;
        return true;
    }
    private Set<String> devices(ContextWebFragment.Item item) {
        Set<String> result = new HashSet<>();
        org.json.JSONArray devices = item.context.optJSONObject("bluetooth").optJSONArray("devices");
        for (int i = 0; i < devices.length(); i++) {
            org.json.JSONObject device = devices.optJSONObject(i);
            if (device != null) result.add(device.optString("id", device.optString("name")));
        }
        return result;
    }
    double distance(ContextWebFragment.Item a, ContextWebFragment.Item b) {
        double distance = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 0) continue;
            Object av = a.value(i), bv = b.value(i); double difference;
            {
                double an = ((Number) av).doubleValue(), bn = ((Number) bv).doubleValue();
                if (i == 1) { double minutes = Math.abs(an - bn) % 1440; difference = Math.min(minutes, 1440 - minutes) / 720; }
                else if (min[i] == max[i]) difference = 0;
                else {
                    double range = max[i] - min[i];
                    difference = Double.isFinite(range) ? Math.abs(an - bn) / range
                            : Math.abs(an / 2 - bn / 2) / (max[i] / 2 - min[i] / 2);
                }
            }
            distance += weights[i] * Math.max(0, Math.min(1, difference));
        }
        return distance;
    }
    private int matches(ContextWebFragment.Item a, ContextWebFragment.Item b) {
        int count = 0;
        for (int i = 5; i <= 7; i++) {
            if (enabled[i] && (i == 6 ? devices(a).equals(devices(b)) : a.value(i).equals(b.value(i)))) count++;
        }
        return count;
    }
    ArrayList<Edge> edges(ArrayList<ContextWebFragment.Item> displayed, int k, long seed) {
        ArrayList<ContextWebFragment.Item> eligible = new ArrayList<>();
        for (ContextWebFragment.Item item : displayed) if (available(item)) eligible.add(item);
        ArrayList<Edge> edges = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ContextWebFragment.Item item : eligible) {
            ArrayList<ContextWebFragment.Item> candidates = new ArrayList<>(eligible); candidates.remove(item);
            java.util.Collections.shuffle(candidates, new java.util.Random(seed ^ Long.parseLong(item.id)));
            candidates.sort((a,b) -> {
                int order = Integer.compare(matches(item, b), matches(item, a));
                return order != 0 ? order : Double.compare(distance(item, a), distance(item, b));
            });
            for (int i = 0; i < Math.min(k, candidates.size()); i++) {
                String other = candidates.get(i).id;
                String key = item.id.compareTo(other) < 0 ? item.id + ":" + other : other + ":" + item.id;
                if (seen.add(key)) edges.add(new Edge(item.id, other));
            }
        }
        return edges;
    }
}
