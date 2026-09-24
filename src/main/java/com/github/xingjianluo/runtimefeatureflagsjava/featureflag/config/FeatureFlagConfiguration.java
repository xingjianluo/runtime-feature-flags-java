package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigValueType;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.BucketHasher;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.RolloutEvaluator;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.store.FeatureFlagStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires schema, layered config (startup layer loaded here and frozen),
 * the deterministic rollout evaluator and the versioned store.
 */
@Configuration
public class FeatureFlagConfiguration {

    /** Prefix for startup-layer values in application.properties. */
    public static final String STARTUP_PREFIX = "featureflag.config.";

    /**
     * Code-defined config schema: types, defaults and constraints.
     * Centralized so merge and validation share one source of truth.
     */
    @Bean
    public ConfigSchema configSchema() {
        List<ConfigKeyDescriptor> descriptors = List.of(
                ConfigKeyDescriptor.builder("service.timeoutMs", ConfigValueType.LONG)
                        .defaultValue("1000").min(0L).max(60_000L).build(),
                ConfigKeyDescriptor.builder("service.maxRetries", ConfigValueType.INT)
                        .defaultValue("3").min(0L).max(10L).build(),
                ConfigKeyDescriptor.builder("ui.theme", ConfigValueType.STRING)
                        .defaultValue("light").allowedValues(Set.of("light", "dark", "system")).build(),
                ConfigKeyDescriptor.builder("cache.enabled", ConfigValueType.BOOLEAN)
                        .defaultValue("true").build(),
                ConfigKeyDescriptor.builder("payment.mode", ConfigValueType.STRING)
                        .required(true).allowedValues(Set.of("sandbox", "production")).build()
        );
        return new ConfigSchema(descriptors);
    }

    /**
     * Startup layer: read once from the environment and frozen for the JVM lifetime.
     * Construction eagerly validates values; an ill-typed/illegal launch value stops
     * context startup rather than being silently ignored.
     */
    @Bean
    public LayeredConfigService layeredConfigService(ConfigSchema schema, Environment environment) {
        Map<String, String> startup = new LinkedHashMap<>();
        for (ConfigKeyDescriptor descriptor : schema.descriptors()) {
            String value = environment.getProperty(STARTUP_PREFIX + descriptor.key());
            if (value != null) {
                startup.put(descriptor.key(), value);
            }
        }
        return new LayeredConfigService(schema, startup);
    }

    @Bean
    public BucketHasher bucketHasher() {
        return new BucketHasher();
    }

    @Bean
    public RolloutEvaluator rolloutEvaluator(BucketHasher bucketHasher) {
        return new RolloutEvaluator(bucketHasher);
    }

    @Bean
    public FeatureFlagStore featureFlagStore(RolloutEvaluator rolloutEvaluator,
                                             LayeredConfigService layeredConfigService) {
        return new FeatureFlagStore(20, System::currentTimeMillis,
                rolloutEvaluator, layeredConfigService);
    }
}
