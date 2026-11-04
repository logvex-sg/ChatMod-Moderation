package com.adminmod.common.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.adminmod.common.model.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Bounded rolling series of numeric samples, one per metric.
 *
 * <p>Each series is a fixed-size ring: once full, the oldest sample is overwritten, so memory is
 * constant no matter how long the server runs. The recorder keeps the last value, the average, the
 * minimum and the maximum of the current window, which is what a dashboard needs; it deliberately
 * does not keep a full history, because that would grow without bound and is what a real metrics
 * backend is for.
 *
 * <p>It is thread-safe so a background sampler can write while the server thread reads.
 */
public final class MetricsRecorder {

    /** A named series with its window statistics. */
    public static final class Series {
        private final String name;
        private final String unit;
        private final double[] samples;
        private int count;
        private int cursor;

        Series(String name, String unit, int capacity) {
            this.name = name;
            this.unit = unit;
            this.samples = new double[Math.max(1, capacity)];
        }

        synchronized void add(double value) {
            samples[cursor] = value;
            cursor = (cursor + 1) % samples.length;
            if (count < samples.length) {
                count++;
            }
        }

        public synchronized double last() {
            if (count == 0) {
                return 0.0;
            }
            int index = (cursor - 1 + samples.length) % samples.length;
            return samples[index];
        }

        public synchronized double average() {
            if (count == 0) {
                return 0.0;
            }
            double sum = 0.0;
            for (int i = 0; i < count; i++) {
                sum += samples[i];
            }
            return sum / count;
        }

        public synchronized double max() {
            if (count == 0) {
                return 0.0;
            }
            double max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                max = Math.max(max, samples[i]);
            }
            return max;
        }

        public synchronized double min() {
            if (count == 0) {
                return 0.0;
            }
            double min = Double.POSITIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                min = Math.min(min, samples[i]);
            }
            return min;
        }

        public synchronized int sampleCount() {
            return count;
        }

        public synchronized double[] snapshot() {
            double[] out = new double[count];
            System.arraycopy(samples, 0, out, 0, count);
            return out;
        }

        public String name() {
            return name;
        }

        public String unit() {
            return unit;
        }

        public synchronized JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("name", name);
            object.addProperty("unit", unit);
            object.addProperty("last", round(last()));
            object.addProperty("avg", round(average()));
            object.addProperty("min", round(min()));
            object.addProperty("max", round(max()));
            object.addProperty("samples", count);
            return object;
        }
    }

    private final int capacity;
    private final Map<String, Series> series = new LinkedHashMap<>();

    public MetricsRecorder(int capacity) {
        this.capacity = Math.max(2, capacity);
    }

    /** Registers a series, or returns the existing one with the same name. */
    public synchronized Series series(String name, String unit) {
        return series.computeIfAbsent(name, key -> new Series(key, unit, capacity));
    }

    public void record(String name, String unit, double value) {
        series(name, unit).add(value);
    }

    public synchronized Series get(String name) {
        return series.get(name);
    }

    public synchronized List<Series> all() {
        return new ArrayList<>(series.values());
    }

    public synchronized void clear() {
        for (Series value : series.values()) {
            value.cursor = 0;
            value.count = 0;
        }
    }

    /** JSON view for the diagnostics dashboard and the export. */
    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Series value : series.values()) {
            array.add(value.toJson());
        }
        root.add("series", array);
        return Json.write(root);
    }

    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
