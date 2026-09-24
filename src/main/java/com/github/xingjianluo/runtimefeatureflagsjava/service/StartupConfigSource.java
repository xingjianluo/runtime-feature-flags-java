package com.github.xingjianluo.runtimefeatureflagsjava.service;

import java.util.Map;

/** 启动配置层（来自 Spring Environment / application.properties），只读。 */
public interface StartupConfigSource {
    Map<String, String> values();
}
