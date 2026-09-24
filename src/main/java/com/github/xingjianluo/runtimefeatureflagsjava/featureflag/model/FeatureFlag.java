package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * A single feature flag definition.
 *
 * @param key          unique flag key, non-blank
 * @param enabled     master switch; when false the flag evaluates DISABLED for every subject
 * @param rule         gray rollout rule
 * @param secretValues values that must never appear in audit logs / explanations
 */
public record FeatureFlag(String key,
                          boolean enabled,
                          RolloutRule rule,
                          java.util.List<String> secretValues) {

    public FeatureFlag {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("flag key must be non-blank");
        }
        if (rule == null) {
            throw new IllegalArgumentException("flag '" + key + "' rule must be non-null");
        }
        secretValues = secretValues == null ? java.util.List.of() : java.util.List.copyOf(secretValues);
    }

    public static FeatureFlag enabled(String key, RolloutRule rule) {
        return new FeatureFlag(key, true, rule, java.util.List.of());
    }

    public static FeatureFlag disabled(String key) {
        return new FeatureFlag(key, false, RolloutRule.off(), java.util.List.of());
    }
}
