package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import java.util.Set;

/**
 * Declares a config key: its type, default value and optional value constraints.
 * Descriptors are code-defined defaults and never change at runtime.
 */
public record ConfigKeyDescriptor(String key,
                                  ConfigValueType type,
                                  String defaultValue,
                                  boolean required,
                                  Long min,
                                  Long max,
                                  Set<String> allowedValues) {

    public ConfigKeyDescriptor {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("descriptor key must be non-blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("descriptor type must be non-null");
        }
        allowedValues = allowedValues == null ? Set.of() : Set.copyOf(allowedValues);
        if (required && defaultValue != null) {
            throw new IllegalArgumentException("required key '" + key + "' cannot declare a default");
        }
    }

    public static Builder builder(String key, ConfigValueType type) {
        return new Builder(key, type);
    }

    /** Compact builder for descriptors. */
    public static final class Builder {
        private final String key;
        private final ConfigValueType type;
        private String defaultValue;
        private boolean required;
        private Long min;
        private Long max;
        private Set<String> allowedValues = Set.of();

        private Builder(String key, ConfigValueType type) {
            this.key = key;
            this.type = type;
        }

        public Builder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder min(long min) {
            this.min = min;
            return this;
        }

        public Builder max(long max) {
            this.max = max;
            return this;
        }

        public Builder allowedValues(Set<String> allowedValues) {
            this.allowedValues = allowedValues;
            return this;
        }

        public ConfigKeyDescriptor build() {
            return new ConfigKeyDescriptor(key, type, defaultValue, required, min, max, allowedValues);
        }
    }
}
