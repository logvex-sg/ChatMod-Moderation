package com.adminmod.common.localchat;

import java.util.List;

import com.adminmod.common.config.AdminConfig;

/**
 * Resolves the effective proximity settings for one dimension.
 *
 * <p>The global {@link AdminConfig.LocalChat} section holds the defaults; a {@link
 * AdminConfig.DimensionRule} for the player's dimension can override the radius, the two formats,
 * the global prefix and the global-reach flag. Resolution lives here, free of Minecraft types, so
 * the fall-back rules ("empty means inherit") are covered by ordinary unit tests.
 */
public final class DimensionRules {

    private DimensionRules() {
    }

    /** The settings that apply to one dimension, after overrides. */
    public record Effective(boolean enabled, double radius, String globalPrefix, String localFormat,
            String globalFormat, boolean globalReachesAll) {
    }

    /**
     * Resolves the settings for {@code dimensionId}.
     *
     * <p>The first rule whose dimension matches wins; a rule that disables the dimension turns
     * proximity chat off there entirely. A blank field in the matching rule inherits the global
     * value, and a radius at or below zero does the same.
     */
    public static Effective resolve(AdminConfig.LocalChat config, String dimensionId) {
        boolean enabled = config.enabled;
        double radius = config.radius;
        String prefix = config.globalPrefix;
        String localFormat = config.localFormat;
        String globalFormat = config.globalFormat;
        boolean globalReachesAll = config.globalReachesAllDimensions;

        AdminConfig.DimensionRule rule = match(config.dimensions, dimensionId);
        if (rule != null) {
            if (!rule.enabled) {
                enabled = false;
            }
            if (rule.radius > 0) {
                radius = rule.radius;
            }
            if (notBlank(rule.globalPrefix)) {
                prefix = rule.globalPrefix;
            }
            if (notBlank(rule.localFormat)) {
                localFormat = rule.localFormat;
            }
            if (notBlank(rule.globalFormat)) {
                globalFormat = rule.globalFormat;
            }
            if (rule.globalReachesAllDimensions != null) {
                globalReachesAll = rule.globalReachesAllDimensions;
            }
        }
        return new Effective(enabled, radius, prefix == null ? "" : prefix,
                localFormat == null ? "" : localFormat, globalFormat == null ? "" : globalFormat,
                globalReachesAll);
    }

    /** Whether a message body is a global message under the effective prefix. */
    public static boolean isGlobal(Effective effective, String message) {
        if (message == null) {
            return false;
        }
        String prefix = effective.globalPrefix();
        return !prefix.isEmpty() && message.startsWith(prefix);
    }

    /** Removes the global prefix from a message, leaving the body to render. */
    public static String stripPrefix(Effective effective, String message) {
        if (message == null) {
            return "";
        }
        String prefix = effective.globalPrefix();
        if (!prefix.isEmpty() && message.startsWith(prefix)) {
            return message.substring(prefix.length()).stripLeading();
        }
        return message;
    }

    private static AdminConfig.DimensionRule match(List<AdminConfig.DimensionRule> rules, String dimensionId) {
        if (rules == null || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        for (AdminConfig.DimensionRule rule : rules) {
            if (rule == null || rule.dimension == null || rule.dimension.isBlank()) {
                continue;
            }
            if (rule.dimension.equalsIgnoreCase(dimensionId.trim())) {
                return rule;
            }
        }
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
