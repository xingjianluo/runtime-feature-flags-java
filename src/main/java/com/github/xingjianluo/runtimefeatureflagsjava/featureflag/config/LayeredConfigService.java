package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.ConfigTypeMismatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidConfigValueException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.MissingConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigLayer;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigValueType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Merges the three config layers for a key and validates every value.
 *
 * <p>Priority (highest wins), with clear effective time:
 * <ol>
 *   <li>{@link ConfigLayer#RUNTIME} – atomically published overrides, updated on each publish</li>
 *   <li>{@link ConfigLayer#STARTUP} – loaded once at construction and frozen for the JVM lifetime</li>
 *   <li>{@link ConfigLayer#DEFAULT}  – code-declared defaults from the {@link ConfigSchema}</li>
 * </ol>
 * Missing, non-convertible or constraint-violating values are rejected with a typed
 * exception rather than silently adopted. Startup values are validated eagerly so a bad
 * launch configuration fails fast instead of being ignored.
 */
public class LayeredConfigService {

    private final ConfigSchema schema;
    private final Map<String, String> startupValues;
    private volatile Supplier<Map<String, String>> runtimeView = Map::of;

    public LayeredConfigService(ConfigSchema schema, Map<String, String> startupValues) {
        this.schema = schema;
        this.startupValues = startupValues == null ? Map.of() : Map.copyOf(startupValues);
        // Fail fast: reject unknown / ill-typed / illegal startup configuration up front.
        for (Map.Entry<String, String> entry : this.startupValues.entrySet()) {
            validateValue(entry.getKey(), entry.getValue());
        }
    }

    /** Attach the live source of runtime overrides (the current published version). */
    public void attachRuntimeView(Supplier<Map<String, String>> runtimeView) {
        if (runtimeView == null) {
            throw new IllegalArgumentException("runtimeView must be non-null");
        }
        this.runtimeView = runtimeView;
    }

    /**
     * Resolve a key across layers and validate the winning value against the schema.
     *
     * @throws MissingConfigException        when absent with no default
     * @throws InvalidConfigValueException   when the key is unknown or value violates constraints
     * @throws ConfigTypeMismatchException   when the value cannot convert to the declared type
     */
    public ResolvedValue resolve(String key) {
        ConfigKeyDescriptor descriptor = requireDescriptor(key);

        Map<String, String> runtime = runtimeView.get();
        String raw;
        ConfigLayer layer;
        if (runtime.containsKey(key)) {
            raw = runtime.get(key);
            layer = ConfigLayer.RUNTIME;
        } else if (startupValues.containsKey(key)) {
            raw = startupValues.get(key);
            layer = ConfigLayer.STARTUP;
        } else {
            raw = descriptor.defaultValue();
            layer = ConfigLayer.DEFAULT;
        }

        if (raw == null) {
            throw new MissingConfigException("no value for config key '" + key
                    + (descriptor.required() ? "' (required)" : "') and no default declared"));
        }
        Object value = convert(descriptor, raw);
        checkConstraints(descriptor, value);
        return new ResolvedValue(key, value, layer, raw);
    }

    public String getString(String key) {
        return (String) resolve(key).value();
    }

    public int getInt(String key) {
        return (Integer) resolve(key).value();
    }

    public long getLong(String key) {
        return (Long) resolve(key).value();
    }

    public boolean getBoolean(String key) {
        return (Boolean) resolve(key).value();
    }

    public double getDouble(String key) {
        return (Double) resolve(key).value();
    }

    /**
     * Validate a raw value for a key without returning it: known key, correct type, legal range.
     * Raw values are never echoed in error messages to avoid leaking sensitive input.
     */
    public void validateValue(String key, String raw) {
        ConfigKeyDescriptor descriptor = requireDescriptor(key);
        if (raw == null || raw.isEmpty()) {
            if (descriptor.required()) {
                throw new MissingConfigException("missing required value for config key '" + key + "'");
            }
            return;
        }
        Object converted = convert(descriptor, raw);
        checkConstraints(descriptor, converted);
    }

    /** Fully merged, typed snapshot of every resolvable key. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigKeyDescriptor descriptor : schema.descriptors()) {
            try {
                out.put(descriptor.key(), resolve(descriptor.key()).value());
            } catch (MissingConfigException ignored) {
                // key currently absent in every layer: omit it rather than fail the whole snapshot
            }
        }
        return Map.copyOf(out);
    }

    private ConfigKeyDescriptor requireDescriptor(String key) {
        return schema.descriptor(key)
                .orElseThrow(() -> new InvalidConfigValueException(
                        "unknown config key '" + key + "'; not declared in schema"));
    }

    private Object convert(ConfigKeyDescriptor descriptor, String raw) {
        String key = descriptor.key();
        try {
            return switch (descriptor.type()) {
                case STRING -> raw;
                case BOOLEAN -> parseBoolean(key, raw);
                case INT -> Integer.valueOf(Integer.parseInt(raw.trim()));
                case LONG -> Long.valueOf(Long.parseLong(raw.trim()));
                case DOUBLE -> Double.valueOf(Double.parseDouble(raw.trim()));
            };
        } catch (NumberFormatException e) {
            throw new ConfigTypeMismatchException("config key '" + key + "' expects "
                    + descriptor.type() + " but got a non-numeric value");
        } catch (IllegalArgumentException e) {
            throw new ConfigTypeMismatchException("config key '" + key + "' expects "
                    + descriptor.type() + " but got an unparseable value");
        }
    }

    private static Boolean parseBoolean(String key, String raw) {
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        throw new ConfigTypeMismatchException("config key '" + key
                + "' expects BOOLEAN (true/false) but got an unparseable value");
    }

    private void checkConstraints(ConfigKeyDescriptor descriptor, Object value) {
        String key = descriptor.key();
        long numeric = 0L;
        boolean isIntegral = value instanceof Integer || value instanceof Long;
        if (isIntegral) {
            numeric = ((Number) value).longValue();
            if (descriptor.min() != null && numeric < descriptor.min()) {
                throw new InvalidConfigValueException("config key '" + key + "' value " + numeric
                        + " is below minimum " + descriptor.min());
            }
            if (descriptor.max() != null && numeric > descriptor.max()) {
                throw new InvalidConfigValueException("config key '" + key + "' value " + numeric
                        + " is above maximum " + descriptor.max());
            }
        }
        if (!descriptor.allowedValues().isEmpty()
                && !descriptor.allowedValues().contains(value.toString())) {
            throw new InvalidConfigValueException("config key '" + key
                    + "' value is not one of the allowed values");
        }
    }

    /** A resolved value plus the layer that supplied it. */
    public record ResolvedValue(String key, Object value, ConfigLayer layer, String raw) {
    }
}
