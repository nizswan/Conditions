package com.example.conditions;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

final class ContentWebPlot extends FrameLayout {
    interface Selection { void open(ArrayList<ContextWebFragment.Item> items); }
    final int plotWidth, plotHeight;
    private final Axis horizontal, vertical, color, size;
    private static final int MIN_DIAMETER = 12, MAX_DIAMETER = 36;
    private static final int LOW_COLOR = 0xFF2563EB, HIGH_COLOR = 0xFFDC2626;
    private final ArrayList<Point> points = new ArrayList<>();
    private final ArrayList<NeighborMetric.Edge> edges = new ArrayList<>();
    private final java.util.Map<String, Point> positions = new java.util.HashMap<>();
    private final java.util.Set<String> highlighted = new java.util.HashSet<>();
    void setEdges(ArrayList<NeighborMetric.Edge> values) { edges.clear(); edges.addAll(values); invalidate(); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float left, top, width, height;
    private static class Point {
        float x, y;
        double sizeValue;
        final ArrayList<ContextWebFragment.Item> items = new ArrayList<>();
    }
    private static class Axis {
        final int type;
        final ArrayList<String> categories = new ArrayList<>();
        final ArrayList<Integer> categoryColors = new ArrayList<>();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        Axis(ArrayList<ContextWebFragment.Item> dataset, int type) {
            this.type = type;
            for (ContextWebFragment.Item p : dataset) {
                Object value = p.value(type);
                if (value instanceof Number) { double n = ((Number) value).doubleValue(); min = Math.min(min, n); max = Math.max(max, n); }
                else if (value instanceof String && !categories.contains(value)) categories.add((String) value);
            }
            categories.sort(String.CASE_INSENSITIVE_ORDER);
            if (!categories.isEmpty()) {
                // Spread hues across the whole palette, then assign them reproducibly without replacement.
                for (int i = 0; i < categories.size(); i++) {
                    float hue = (217f + 360f * i / categories.size()) % 360f;
                    categoryColors.add(i == 0 ? LOW_COLOR : android.graphics.Color.HSVToColor(new float[]{hue, .80f, .86f}));
                }
                java.util.Collections.shuffle(categoryColors, new java.util.Random(42));
            }
        }
        double position(Object value) {
            double normalized;
            if (value instanceof Number) {
                if (max == min) return .5;
                double range = max - min, number = ((Number) value).doubleValue();
                normalized = Double.isFinite(range) ? (number - min) / range
                        : (number / 2 - min / 2) / (max / 2 - min / 2);
            } else normalized = categories.size() <= 1 ? .5 : (double) categories.indexOf(value) / (categories.size() - 1);
            return Double.isFinite(normalized) ? Math.max(0, Math.min(1, normalized)) : .5;
        }
        int color(Object value) {
            if (!categories.isEmpty()) {
                int index = categories.indexOf(value);
                return index < 0 ? LOW_COLOR : categoryColors.get(index);
            }
            return androidx.core.graphics.ColorUtils.blendARGB(LOW_COLOR, HIGH_COLOR, (float) position(value));
        }
        String format(double n) {
            if (type == 0) {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("MM/dd/yy", Locale.getDefault());
                format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return format.format(new java.util.Date(Math.round(n / 86400000.0) * 86400000L));
            }
            if (type == 10) return new java.text.SimpleDateFormat("MM/dd/yy HH:mm:ss", Locale.getDefault())
                    .format(new java.util.Date((long) n));
            if (type == 1) return String.format(Locale.US, "%02d:%02d", (int) n / 60, (int) n % 60);
            if (Math.abs(n) >= 1000000) return String.format(Locale.US, "%.3g", n);
            return String.format(Locale.US, type == 8 || type == 9 ? "%.5f" : "%.1f", n);
        }
        int ticks() {
            if (!categories.isEmpty()) return categories.size();
            if (min == max) return 1;
            return type == 0 && max - min <= 86400000L ? 2 : 3;
        }
        double tickPosition(int i) { return ticks() == 1 ? .5 : (double) i / (ticks() - 1); }
        String tickLabel(int i) { return categories.isEmpty() ? format(min == max ? min : (1 - tickPosition(i)) * min + tickPosition(i) * max) : categories.get(i); }
    }
    ContentWebPlot(Context context, ArrayList<ContextWebFragment.Item> dataset,
                   ArrayList<ContextWebFragment.Item> plotted, int x, int y, int colorType, int sizeType, Selection selection) {
        super(context); setWillNotDraw(false); setBackgroundColor(0xFFFFFFFF);
        horizontal = new Axis(dataset, x); vertical = y < 0 ? null : new Axis(dataset, y);
        color = colorType < 0 ? null : new Axis(dataset, colorType);
        size = sizeType < 0 ? null : new Axis(dataset, sizeType);
        int margin = vertical == null ? 30 : !vertical.categories.isEmpty() ? 112
                : vertical.type == 0 || vertical.type == 10 || vertical.type == 8 || vertical.type == 9 ? 96 : 64;
        left = dp(margin); top = dp(48);
        float available = getResources().getDisplayMetrics().widthPixels - dp(32) - left - dp(32);
        width = Math.max(Math.max(dp(180), available), dp((horizontal.categories.size() - 1) * 100));
        height = dp(vertical == null ? 160 : Math.max(360, (vertical.categories.size() - 1) * 60));
        plotWidth = (int) (left + width + dp(32)); plotHeight = (int) (top + height + dp(96));
        for (ContextWebFragment.Item p : plotted) {
            float px = left + (float) horizontal.position(p.value(x)) * width;
            float py = top + (vertical == null ? height / 2 : (1 - (float) vertical.position(p.value(y))) * height);
            Point match = null;
            for (Point point : points) {
                ContextWebFragment.Item first = point.items.get(0);
                if (java.util.Objects.equals(first.value(x), p.value(x))
                        && (y < 0 || java.util.Objects.equals(first.value(y), p.value(y)))
                        && (color == null || java.util.Objects.equals(first.value(colorType), p.value(colorType)))
                        && (size == null || java.util.Objects.equals(first.value(sizeType), p.value(sizeType)))) { match = point; break; }
            }
            if (match == null) {
                match = new Point(); match.x = px; match.y = py;
                match.sizeValue = size == null ? .5 : size.position(p.value(sizeType));
                points.add(match);
            }
            match.items.add(p); positions.put(p.id, match);
        }
        for (Point point : points) {
            FrameLayout target = new FrameLayout(context);
            int diameter = size == null ? dp(18) : dp(MIN_DIAMETER) + (int) Math.round(point.sizeValue * dp(MAX_DIAMETER - MIN_DIAMETER));
            android.view.View dot = new android.view.View(context);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(color == null ? LOW_COLOR : color.color(point.items.get(0).value(colorType)));
            circle.setStroke(dp(1), 0xFFFFFFFF); dot.setBackground(circle);
            target.addView(dot, new LayoutParams(diameter, diameter, android.view.Gravity.CENTER));
            if (point.items.size() > 1) {
                TextView count = new TextView(context); count.setText(String.valueOf(point.items.size())); count.setTextSize(11); count.setTextColor(0xFF252A31);
                count.setBackgroundColor(0xEEFFFFFF);
                target.addView(count, new LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.RIGHT));
            }
            target.setContentDescription(point.items.size() == 1 ? "Open " + point.items.get(0).title : "Choose from " + point.items.size() + " items");
            target.setFocusable(true); target.setOnClickListener(v -> {
                ArrayList<ContextWebFragment.Item> nearby = new ArrayList<>();
                for (Point other : points) if (Math.hypot(point.x - other.x, point.y - other.y) <= dp(28)) nearby.addAll(other.items);
                highlighted.clear(); for (ContextWebFragment.Item item : nearby) highlighted.add(item.id); invalidate();
                selection.open(nearby);
            });
            LayoutParams bounds = new LayoutParams(dp(48), dp(48)); bounds.leftMargin = Math.round(point.x) - dp(24); bounds.topMargin = Math.round(point.y) - dp(24);
            addView(target, bounds);
        }
    }
    void addLegends(android.widget.LinearLayout list) {
        if (color != null) {
            legendText(list, "Color · " + ContextWebFragment.AXES[color.type]);
            if (!color.categories.isEmpty()) for (String category : color.categories) legendDot(list, color.color(category), 18, category);
            else {
                android.view.View gradient = new android.view.View(getContext());
                gradient.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{color.min == color.max ? color.color(color.min) : LOW_COLOR, color.min == color.max ? color.color(color.max) : HIGH_COLOR}));
                list.addView(gradient, new android.widget.LinearLayout.LayoutParams(-1, dp(12)));
                legendText(list, color.format(color.min) + "  →  " + color.format(color.max));
            }
        }
        if (size != null) {
            legendText(list, "Dot size · " + ContextWebFragment.AXES[size.type]);
            if (!size.categories.isEmpty()) for (String category : size.categories)
                legendDot(list, 0xFF626873, MIN_DIAMETER + (int) Math.round(size.position(category) * (MAX_DIAMETER - MIN_DIAMETER)), category);
            else if (size.min == size.max) legendDot(list, 0xFF626873, (MIN_DIAMETER + MAX_DIAMETER) / 2, size.format(size.min));
            else {
                legendDot(list, 0xFF626873, MIN_DIAMETER, size.format(size.min));
                legendDot(list, 0xFF626873, MAX_DIAMETER, size.format(size.max));
            }
        }
    }
    private void legendText(android.widget.LinearLayout list, String value) {
        TextView label = new TextView(getContext()); label.setText(value); label.setTextSize(13); label.setTextColor(0xFF252A31);
        label.setPadding(0, dp(8), 0, dp(8)); list.addView(label);
    }
    private void legendDot(android.widget.LinearLayout list, int tint, int diameter, String value) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(getContext()); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        FrameLayout sample = new FrameLayout(getContext()); android.view.View dot = new android.view.View(getContext());
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL); circle.setColor(tint); dot.setBackground(circle);
        sample.addView(dot, new LayoutParams(dp(diameter), dp(diameter), android.view.Gravity.CENTER));
        row.addView(sample, new android.widget.LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView label = new TextView(getContext()); label.setText(value); label.setTextSize(13); label.setTextColor(0xFF252A31);
        row.addView(label, new android.widget.LinearLayout.LayoutParams(0, -2, 1)); list.addView(row);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); paint.setStrokeWidth(dp(1)); paint.setTextSize(dp(13));
        drawAxis(canvas, horizontal, true);
        if (vertical != null) drawAxis(canvas, vertical, false);
        for (NeighborMetric.Edge edge : edges) {
            Point a = positions.get(edge.a), b = positions.get(edge.b);
            if (a == null || b == null || a == b) continue;
            boolean selected = highlighted.contains(edge.a) || highlighted.contains(edge.b);
            paint.setColor(selected ? 0xDD2563EB : 0x55808DA3); paint.setStrokeWidth(dp(selected ? 2 : 1));
            canvas.drawLine(a.x, a.y, b.x, b.y, paint);
        }
        paint.setColor(0xFF252A31); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(14));
        canvas.drawText(ContextWebFragment.AXES[horizontal.type], left + width / 2, plotHeight - dp(12), paint);
        if (vertical != null) { paint.setTextAlign(Paint.Align.LEFT); canvas.drawText(ContextWebFragment.AXES[vertical.type], dp(8), dp(20), paint); }
    }
    private void drawAxis(Canvas canvas, Axis axis, boolean x) {
        for (int i = 0; i < axis.ticks(); i++) {
            float pos = (float) axis.tickPosition(i);
            float px = left + pos * width, py = top + (1 - pos) * height;
            paint.setColor(0xFFE1E5EB);
            if (x) canvas.drawLine(px, top, px, top + height, paint); else canvas.drawLine(left, py, left + width, py, paint);
            paint.setColor(0xFF424A57);
            paint.setTextAlign(!x ? Paint.Align.RIGHT : axis.ticks() == 1 ? Paint.Align.CENTER
                    : i == 0 ? Paint.Align.LEFT : i == axis.ticks() - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            String value = axis.tickLabel(i);
            // Wrap labels into short lines so categorical positions remain readable.
            int limit = x ? 14 : 12;
            int line = 0;
            while (!value.isEmpty()) {
                int end = Math.min(limit, value.length());
                if (value.length() > limit) {
                    int space = value.lastIndexOf(' ', end);
                    if (space > 0) end = space;
                }
                String part = value.substring(0, end); value = value.substring(end).trim();
                canvas.drawText(part, x ? px : left - dp(18), x ? top + height + dp(32 + line * 16) : py + dp(4 + line * 16), paint);
                if (++line == 3) break;
            }
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
