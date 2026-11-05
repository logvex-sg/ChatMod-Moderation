package com.adminmod.common.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata for one configurable value.
 *
 * <p>A spec is what makes the central settings screen possible: it says where the value lives on
 * {@code AdminConfig} (the {@link #path()}), how it is presented (type, range, options, unit), how
 * it is protected (advanced, dangerous, restart, permission) and how it is documented (label,
 * description, recommendations). Every subsystem registers its settings as specs, so the GUI never
 * needs to know about a specific field.
 *
 * <p>A spec is immutable once built. {@link #defaultValue()} is captured from a freshly constructed
 * {@code AdminConfig} by {@link SettingsBinder}, which is what makes "reset to default" mean the
 * documented default rather than whatever the last operator typed.
 */
public final class SettingSpec {

    private final String path;
    private final String label;
    private final String description;
    private final SettingCategory category;
    private final String module;
    private final SettingType type;
    private final String defaultValue;
    private final Double min;
    private final Double max;
    private final Double step;
    private final List<String> options;
    private final String unit;
    private final int maxLength;
    private final boolean advanced;
    private final boolean dangerous;
    private final boolean restartRequired;
    private final boolean secret;
    private final boolean deprecated;
    private final String since;
    private final String dependsOn;
    private final List<String> conflictsWith;
    private final Map<String, String> recommendations;

    private SettingSpec(Builder builder) {
        this.path = builder.path;
        this.label = builder.label;
        this.description = builder.description;
        this.category = builder.category;
        this.module = builder.module;
        this.type = builder.type;
        this.defaultValue = builder.defaultValue;
        this.min = builder.min;
        this.max = builder.max;
        this.step = builder.step;
        this.options = List.copyOf(builder.options);
        this.unit = builder.unit;
        this.maxLength = builder.maxLength;
        this.advanced = builder.advanced;
        this.dangerous = builder.dangerous;
        this.restartRequired = builder.restartRequired;
        this.secret = builder.secret;
        this.deprecated = builder.deprecated;
        this.since = builder.since;
        this.dependsOn = builder.dependsOn;
        this.conflictsWith = List.copyOf(builder.conflictsWith);
        this.recommendations = Map.copyOf(builder.recommendations);
    }

    public static Builder builder(String path, SettingCategory category, SettingType type) {
        return new Builder(path, category, type);
    }

    /**
     * Returns a copy of this spec with a different documented default.
     *
     * <p>The default is normally read from a freshly constructed {@code AdminConfig}, so a spec
     * declared in the catalog does not have to repeat the model's default and cannot drift from it.
     */
    public SettingSpec withDefault(String value) {
        Builder builder = new Builder(path, category, type);
        builder.label = label;
        builder.description = description;
        builder.module = module;
        builder.defaultValue = value;
        builder.min = min;
        builder.max = max;
        builder.step = step;
        builder.options.addAll(options);
        builder.unit = unit;
        builder.maxLength = maxLength;
        builder.advanced = advanced;
        builder.dangerous = dangerous;
        builder.restartRequired = restartRequired;
        builder.secret = secret;
        builder.deprecated = deprecated;
        builder.since = since;
        builder.dependsOn = dependsOn;
        builder.conflictsWith.addAll(conflictsWith);
        builder.recommendations.putAll(recommendations);
        return builder.build();
    }

    public String path() {
        return path;
    }

    /** The field path within {@code AdminConfig}, for example {@code chatBridge.queueCapacity}. */
    public String[] pathParts() {
        return path.split("\\.");
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public SettingCategory category() {
        return category;
    }

    /** The subsystem that owns the setting, shown on the diagnostics page. */
    public String module() {
        return module;
    }

    public SettingType type() {
        return type;
    }

    /** The documented default, rendered as a string. */
    public String defaultValue() {
        return defaultValue;
    }

    public Double min() {
        return min;
    }

    public Double max() {
        return max;
    }

    public Double step() {
        return step;
    }

    public List<String> options() {
        return options;
    }

    public String unit() {
        return unit;
    }

    public int maxLength() {
        return maxLength;
    }

    /** Hidden behind the advanced toggle unless the operator opts in. */
    public boolean advanced() {
        return advanced;
    }

    /** Requires an explicit confirmation before it may be changed. */
    public boolean dangerous() {
        return dangerous;
    }

    public boolean restartRequired() {
        return restartRequired;
    }

    /** A credential: the value is never sent to a client, only whether it is set. */
    public boolean secret() {
        return secret;
    }

    public boolean deprecated() {
        return deprecated;
    }

    public String since() {
        return since;
    }

    /** Another setting path this one needs; empty when there is no dependency. */
    public String dependsOn() {
        return dependsOn;
    }

    public List<String> conflictsWith() {
        return conflictsWith;
    }

    /** Recommended value per server size, keyed by profile/preset name. */
    public Map<String, String> recommendations() {
        return recommendations;
    }

    /** @return whether {@code value} is a syntactically valid value for this spec. */
    public boolean hasRange() {
        return min != null && max != null;
    }

    @Override
    public String toString() {
        return path + " [" + type + "]";
    }

    /** Mutable builder; every field has a sensible default so a spec only states what is special. */
    public static final class Builder {
        private final String path;
        private final SettingCategory category;
        private final SettingType type;
        private String label;
        private String description = "";
        private String module = "core";
        private String defaultValue = "";
        private Double min;
        private Double max;
        private Double step;
        private final List<String> options = new ArrayList<>();
        private String unit = "";
        private int maxLength = 256;
        private boolean advanced;
        private boolean dangerous;
        private boolean restartRequired;
        private boolean secret;
        private boolean deprecated;
        private String since = "1.0.0";
        private String dependsOn = "";
        private final List<String> conflictsWith = new ArrayList<>();
        private final Map<String, String> recommendations = new LinkedHashMap<>();

        private Builder(String path, SettingCategory category, SettingType type) {
            this.path = path;
            this.category = category;
            this.type = type;
            this.label = humanise(path);
        }

        public Builder label(String value) {
            this.label = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder module(String value) {
            this.module = value;
            return this;
        }

        public Builder defaultValue(String value) {
            this.defaultValue = value;
            return this;
        }

        public Builder range(double low, double high) {
            this.min = low;
            this.max = high;
            return this;
        }

        public Builder step(double value) {
            this.step = value;
            return this;
        }

        public Builder options(String... values) {
            this.options.addAll(List.of(values));
            return this;
        }

        public Builder options(List<String> values) {
            this.options.addAll(values);
            return this;
        }

        public Builder unit(String value) {
            this.unit = value;
            return this;
        }

        public Builder maxLength(int value) {
            this.maxLength = value;
            return this;
        }

        public Builder advanced() {
            this.advanced = true;
            return this;
        }

        public Builder dangerous() {
            this.dangerous = true;
            return this;
        }

        public Builder restartRequired() {
            this.restartRequired = true;
            return this;
        }

        public Builder secret() {
            this.secret = true;
            return this;
        }

        public Builder deprecated() {
            this.deprecated = true;
            return this;
        }

        public Builder since(String value) {
            this.since = value;
            return this;
        }

        public Builder dependsOn(String value) {
            this.dependsOn = value;
            return this;
        }

        public Builder conflictsWith(String... values) {
            this.conflictsWith.addAll(List.of(values));
            return this;
        }

        public Builder recommend(String profile, String value) {
            this.recommendations.put(profile, value);
            return this;
        }

        public SettingSpec build() {
            return new SettingSpec(this);
        }

        /** Turns {@code chatBridge.queueCapacity} into {@code Chat bridge queue capacity}. */
        private static String humanise(String path) {
            String tail = path.substring(path.lastIndexOf('.') + 1);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (i > 0 && Character.isUpperCase(c)) {
                    builder.append(' ');
                }
                builder.append(Character.toLowerCase(c));
            }
            String result = builder.toString();
            return result.isEmpty() ? path : Character.toUpperCase(result.charAt(0)) + result.substring(1);
        }
    }
}
