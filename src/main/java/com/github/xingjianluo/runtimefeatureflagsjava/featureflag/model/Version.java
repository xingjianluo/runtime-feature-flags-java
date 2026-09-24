package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import java.util.List;
import java.util.Map;

/**
 * An immutable, complete snapshot of all flags and runtime config values.
 * Readers always observe exactly one whole Version, never a partially applied batch.
 *
 * @param versionNumber monotonically increasing, starting at 0 for the bootstrap version
 * @param flags         full flag map (flag key -> flag)
 * @param configValues  full runtime override map (config key -> raw value)
 * @param publishedAt   epoch millis
 * @param operator      who created this version
 * @param reason        why (may indicate a rollback and the source version)
 * @param originVersion version in which each present key originated
 * @param everKnownKeys keys that have ever existed, so an absent key is distinguishable
 *                      as ROLLED_BACK (seen before) vs UNKNOWN_FLAG (never seen)
 */
public record Version(long versionNumber,
                      Map<String, FeatureFlag> flags,
                      Map<String, String> configValues,
                      long publishedAt,
                      String operator,
                      String reason,
                      Map<String, String> originVersion,
                      java.util.Set<String> everKnownKeys) {

    public Version {
        flags = flags == null ? Map.of() : Map.copyOf(flags);
        configValues = configValues == null ? Map.of() : Map.copyOf(configValues);
        originVersion = originVersion == null ? Map.of() : Map.copyOf(originVersion);
        everKnownKeys = everKnownKeys == null ? java.util.Set.of() : java.util.Set.copyOf(everKnownKeys);
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("version operator must be non-blank");
        }
        reason = reason == null ? "" : reason;
    }

    /** Bootstrap version 0. */
    public static Version bootstrap(long publishedAt,
                                    Map<String, FeatureFlag> flags,
                                    Map<String, String> configValues) {
        java.util.Set<String> known = new java.util.HashSet<>();
        known.addAll(flags.keySet());
        known.addAll(configValues.keySet());
        return new Version(0, flags, configValues, publishedAt, "system", "bootstrap",
                Map.of(), known);
    }

    /** Keys that existed in the previous snapshot but not in this one (removals). */
    public List<String> removedKeysComparedTo(Version previous) {
        return previous.flags().keySet().stream()
                .filter(k -> !flags.containsKey(k))
                .toList();
    }
}
