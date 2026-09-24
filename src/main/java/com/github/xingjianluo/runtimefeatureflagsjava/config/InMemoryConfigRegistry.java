package com.github.xingjianluo.runtimefeatureflagsjava.config;

import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigDefinition;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigRegistry;

import com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigDefinition;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于内存 Map 的配置定义注册表。
 *
 * <p>注册表在应用启动期构建完成，运行期只读。重复注册同一键会快速失败，
 * 避免定义被静默覆盖。
 */
public class InMemoryConfigRegistry implements ConfigRegistry {
    private final Map<String, ConfigDefinition> definitions;

    public InMemoryConfigRegistry() {
        this.definitions = new LinkedHashMap<>();
    }

    /** 注册一个配置定义；重复键直接拒绝。 */
    public InMemoryConfigRegistry register(ConfigDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("配置定义不得为 null");
        }
        ConfigDefinition previous = definitions.putIfAbsent(definition.key(), definition);
        if (previous != null) {
            throw new InvalidValueConfigException("配置键重复注册: " + definition.key());
        }
        return this;
    }

    /** 注册完成后冻结注册表，运行期不可再变。 */
    public ConfigRegistry freeze() {
        Map<String, ConfigDefinition> frozen = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        return new ConfigRegistry() {
            @Override
            public Optional<ConfigDefinition> find(String key) {
                return Optional.ofNullable(frozen.get(key));
            }

            @Override
            public Iterable<ConfigDefinition> all() {
                return frozen.values();
            }
        };
    }

    @Override
    public Optional<ConfigDefinition> find(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    @Override
    public Iterable<ConfigDefinition> all() {
        return Collections.unmodifiableCollection(definitions.values());
    }
}
