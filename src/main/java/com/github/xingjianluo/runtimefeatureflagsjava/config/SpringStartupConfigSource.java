package com.github.xingjianluo.runtimefeatureflagsjava.config;

import com.github.xingjianluo.runtimefeatureflagsjava.service.StartupConfigSource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 启动配置层：从 Spring {@link Environment} 读取前缀为 {@value #PREFIX} 的属性。
 *
 * <p>例如 {@code feature.config.page-size=20} 对应逻辑配置键 {@code page-size}。
 * 多属性源（application.properties、环境变量、JVM 参数等）的优先级完全交由
 * Spring Environment 的标准顺序决定，本类只负责枚举键并取 Environment 解析后的
 * 最终值，避免与 Spring 的覆盖语义产生分歧。
 *
 * <p>该层在应用启动时快照一次，运行期只读；运行期修改由发布版本负责。
 */
public class SpringStartupConfigSource implements StartupConfigSource {
    public static final String PREFIX = "feature.config.";

    private final Map<String, String> snapshot;

    public SpringStartupConfigSource(Environment environment) {
        this.snapshot = Map.copyOf(collect(environment));
    }

    private static Map<String, String> collect(Environment environment) {
        Map<String, String> keys = new TreeMap<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        if (name.startsWith(PREFIX) && name.length() > PREFIX.length()) {
                            keys.putIfAbsent(name.substring(PREFIX.length()), "");
                        }
                    }
                }
            }
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String logicalKey : keys.keySet()) {
            // 统一通过 Environment 取值，确保采用属性源的标准优先级
            String value = environment.getProperty(PREFIX + logicalKey);
            if (value != null) {
                resolved.put(logicalKey, value);
            }
        }
        return resolved;
    }

    @Override
    public Map<String, String> values() {
        return snapshot;
    }
}
