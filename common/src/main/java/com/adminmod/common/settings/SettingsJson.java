package com.adminmod.common.settings;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.adminmod.common.config.AdminConfig;

/**
 * Renders the settings catalogue, values and diagnostics to the JSON the dashboard consumes.
 *
 * <p>Keeping the rendering here, in the shared module, means the server and the client agree on the
 * shape by construction, and it can be unit-tested without a running server. Secret settings are
 * rendered with their value replaced by a flag, so the payload that reaches a client can never carry
 * a credential.
 */
public final class SettingsJson {

    private SettingsJson() {
    }

    /** Renders the whole catalogue plus the current value of every setting. */
    public static String catalog(SettingRegistry registry, AdminConfig config) {
        JsonObject root = new JsonObject();
        JsonArray categories = new JsonArray();
        for (SettingCategory category : registry.categories()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", category.name());
            object.addProperty("label", category.label());
            object.addProperty("description", category.description());
            categories.add(object);
        }
        root.add("categories", categories);

        JsonArray settings = new JsonArray();
        for (SettingSpec spec : registry.all()) {
            settings.add(spec(spec, config));
        }
        root.add("settings", settings);

        JsonArray problems = new JsonArray();
        for (String problem : registry.problems()) {
            problems.add(problem);
        }
        root.add("problems", problems);
        return root.toString();
    }

    /** Renders one setting's metadata and its live value. */
    public static JsonObject spec(SettingSpec spec, AdminConfig config) {
        JsonObject object = new JsonObject();
        object.addProperty("path", spec.path());
        object.addProperty("label", spec.label());
        object.addProperty("description", spec.description());
        object.addProperty("category", spec.category().name());
        object.addProperty("module", spec.module());
        object.addProperty("type", spec.type().name());
        object.addProperty("default", spec.defaultValue());
        if (spec.min() != null) {
            object.addProperty("min", spec.min());
        }
        if (spec.max() != null) {
            object.addProperty("max", spec.max());
        }
        if (spec.step() != null) {
            object.addProperty("step", spec.step());
        }
        if (!spec.options().isEmpty()) {
            JsonArray options = new JsonArray();
            for (String option : spec.options()) {
                options.add(option);
            }
            object.add("options", options);
        }
        object.addProperty("unit", spec.unit());
        object.addProperty("advanced", spec.advanced());
        object.addProperty("dangerous", spec.dangerous());
        object.addProperty("restartRequired", spec.restartRequired());
        object.addProperty("secret", spec.secret());
        object.addProperty("deprecated", spec.deprecated());
        object.addProperty("dependsOn", spec.dependsOn());
        if (!spec.recommendations().isEmpty()) {
            JsonObject recommends = new JsonObject();
            for (Map.Entry<String, String> entry : spec.recommendations().entrySet()) {
                recommends.addProperty(entry.getKey(), entry.getValue());
            }
            object.add("recommends", recommends);
        }
        if (spec.secret()) {
            String value = SettingsBinder.read(config, spec.path());
            object.addProperty("value", "");
            object.addProperty("configured", value != null && !value.isBlank());
        } else {
            object.addProperty("value", SettingsBinder.read(config, spec.path()));
        }
        return object;
    }

    /** Renders the diagnostics findings. */
    public static String diagnostics(List<ConfigDiagnostics.Finding> findings) {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        for (ConfigDiagnostics.Finding finding : findings) {
            JsonObject object = new JsonObject();
            object.addProperty("severity", finding.severity().name());
            object.addProperty("category", finding.category());
            object.addProperty("path", finding.path());
            object.addProperty("message", finding.message());
            array.add(object);
            switch (finding.severity()) {
                case ERROR -> errors++;
                case WARNING -> warnings++;
                case INFO -> infos++;
            }
        }
        root.add("findings", array);
        JsonObject counts = new JsonObject();
        counts.addProperty("error", errors);
        counts.addProperty("warning", warnings);
        counts.addProperty("info", infos);
        root.add("counts", counts);
        return root.toString();
    }

    /** Renders the recent change history. */
    public static String history(List<ConfigHistory.Change> changes) {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (ConfigHistory.Change change : changes) {
            JsonObject object = new JsonObject();
            object.addProperty("at", change.at());
            object.addProperty("actor", change.actor());
            object.addProperty("summary", change.summary());
            object.addProperty("single", change.single());
            JsonObject before = new JsonObject();
            change.before().forEach(before::addProperty);
            JsonObject after = new JsonObject();
            change.after().forEach(after::addProperty);
            object.add("before", before);
            object.add("after", after);
            array.add(object);
        }
        root.add("changes", array);
        return root.toString();
    }

    /** Renders the list of profiles and presets. */
    public static String profiles(List<ConfigProfile> profiles, String selected) {
        JsonObject root = new JsonObject();
        root.addProperty("selected", selected);
        JsonArray array = new JsonArray();
        for (ConfigProfile profile : profiles) {
            JsonObject object = new JsonObject();
            object.addProperty("name", profile.name());
            object.addProperty("description", profile.description());
            object.addProperty("builtIn", profile.builtIn());
            object.addProperty("size", profile.size());
            array.add(object);
        }
        root.add("profiles", array);
        return root.toString();
    }

    /** Renders the preview of the settings a profile would change. */
    public static String preview(String profileName, List<ConfigProfile.Difference> differences) {
        JsonObject root = new JsonObject();
        root.addProperty("profile", profileName);
        JsonArray array = new JsonArray();
        for (ConfigProfile.Difference difference : differences) {
            JsonObject object = new JsonObject();
            object.addProperty("path", difference.path());
            object.addProperty("label", difference.label());
            object.addProperty("current", difference.current());
            object.addProperty("proposed", difference.proposed());
            array.add(object);
        }
        root.add("changes", array);
        root.addProperty("count", array.size());
        return root.toString();
    }
}
