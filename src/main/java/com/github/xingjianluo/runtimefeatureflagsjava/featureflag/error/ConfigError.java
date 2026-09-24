package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/**
 * Structured description of why a single config entry was rejected.
 *
 * @param key    offending key (may be a flag key)
 * @param type   distinguishable machine-readable error kind
 * @param detail human-readable explanation; must never embed raw secret values
 */
public record ConfigError(String key, Type type, String detail) {

    public enum Type {
        MISSING,
        TYPE_MISMATCH,
        INVALID_VALUE,
        UNKNOWN_KEY
    }

    public ConfigError {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ConfigError.key must be non-blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("ConfigError.type must be non-null");
        }
        detail = detail == null ? "" : detail;
    }
}
