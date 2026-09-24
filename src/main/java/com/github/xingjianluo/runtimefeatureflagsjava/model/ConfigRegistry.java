package com.github.xingjianluo.runtimefeatureflagsjava.model;

import java.util.Optional;

/** 配置项定义注册表。 */
public interface ConfigRegistry {
    Optional<ConfigDefinition> find(String key);

    Iterable<ConfigDefinition> all();
}
