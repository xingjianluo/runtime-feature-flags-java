package com.github.xingjianluo.runtimefeatureflagsjava.config;

import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigDefinition;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigRegistry;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigType;
import com.github.xingjianluo.runtimefeatureflagsjava.service.FeatureFlagService;
import com.github.xingjianluo.runtimefeatureflagsjava.service.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.service.StartupConfigSource;
import com.github.xingjianluo.runtimefeatureflagsjava.service.VersionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 组装注册表、分层配置、版本存储与门面的 Spring 配置。
 *
 * <p>Bean 构造期间即完成启动配置校验：application.properties 中任何未知键、
 * 类型不符或越界取值都会导致上下文启动失败（快速失败，不静默采用）。
 */
@Configuration
public class FeatureFlagConfiguration {

    /** 内置示例配置定义；真实项目可改为从声明文件或构建产物加载。 */
    @Bean
    public ConfigRegistry configRegistry() {
        InMemoryConfigRegistry registry = new InMemoryConfigRegistry();
        registry.register(new ConfigDefinition(
                "page-size", ConfigType.INTEGER, "列表分页大小", "20", 1L, 200L, false));
        registry.register(new ConfigDefinition(
                "request-timeout-ms", ConfigType.LONG, "下游请求超时（毫秒）", "3000", 100L, 60000L, false));
        registry.register(new ConfigDefinition(
                "dark-mode-default", ConfigType.BOOLEAN, "是否默认开启深色模式", "false", null, null, false));
        registry.register(new ConfigDefinition(
                "api-gateway-secret", ConfigType.STRING, "下游网关密钥（敏感示例）", null, null, null, true));
        return registry.freeze();
    }

    @Bean
    public StartupConfigSource startupConfigSource(Environment environment) {
        return new SpringStartupConfigSource(environment);
    }

    @Bean
    public VersionStore versionStore(ConfigRegistry configRegistry,
                                     @Value("${feature.history-limit:20}") int historyLimit) {
        return new VersionStore(configRegistry, historyLimit);
    }

    @Bean
    public LayeredConfigService layeredConfigService(ConfigRegistry configRegistry,
                                                     StartupConfigSource startupConfigSource,
                                                     VersionStore versionStore) {
        // 运行时覆盖层直接以版本存储当前快照为事实源，与开关判定共用同一版本
        return new LayeredConfigService(configRegistry, startupConfigSource,
                () -> versionStore.current().configValues());
    }

    @Bean
    public FeatureFlagService featureFlagService(VersionStore versionStore,
                                                 LayeredConfigService layeredConfigService) {
        return new FeatureFlagService(versionStore, layeredConfigService);
    }
}
