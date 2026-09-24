package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Code-defined registry of known config keys (types, defaults, constraints). */
public class ConfigSchema {

    private final Map<String, ConfigKeyDescriptor> descriptors;

    public ConfigSchema(Collection<ConfigKeyDescriptor> descriptors) {
        Map<String, ConfigKeyDescriptor> map = new LinkedHashMap<>();
        if (descriptors != null) {
            for (ConfigKeyDescriptor descriptor : descriptors) {
                if (descriptor == null) {
                    throw new IllegalArgumentException("descriptor must be non-null");
                }
                ConfigKeyDescriptor previous = map.put(descriptor.key(), descriptor);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate config key descriptor: " + descriptor.key());
                }
            }
        }
        this.descriptors = Collections.unmodifiableMap(map);
    }

    public Optional<ConfigKeyDescriptor> descriptor(String key) {
        return Optional.ofNullable(descriptors.get(key));
    }

    public List<ConfigKeyDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    /** Whether a key is declared in code. */
    public boolean isKnown(String key) {
        return descriptors.containsKey(key);
    }
}
