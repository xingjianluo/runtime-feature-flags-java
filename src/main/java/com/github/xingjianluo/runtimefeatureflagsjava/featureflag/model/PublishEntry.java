package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * One atomic upsert entry in a publish batch.
 *
 * @param flag   non-null when upserting a feature flag
 * @param configKey non-null when upserting a runtime config value
 * @param value  raw config value for config entries; null for flag entries
 */
public record PublishEntry(FeatureFlag flag, String configKey, String value) {

    public boolean isFlagEntry() {
        return flag != null;
    }

    public boolean isConfigEntry() {
        return configKey != null;
    }

    public static PublishEntry flag(FeatureFlag flag) {
        if (flag == null) {
            throw new IllegalArgumentException("flag must be non-null");
        }
        return new PublishEntry(flag, null, null);
    }

    public static PublishEntry config(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key must be non-blank");
        }
        return new PublishEntry(null, key, value);
    }

    public String entryKey() {
        return isFlagEntry() ? flag.key() : configKey;
    }
}
