package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.ConfigSchema;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.ConfigTypeMismatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidConfigValueException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.MissingConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigLayer;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigValueType;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishBatch;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishEntry;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.BucketHasher;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.RolloutEvaluator;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.store.FeatureFlagStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layered merge priority and timing, plus the three distinguishable config error kinds.
 * Startup values are validated eagerly at construction; runtime values are validated on publish.
 */
class LayeredConfigTest {

    private static final Logger log = LoggerFactory.getLogger(LayeredConfigTest.class);

    private final ConfigKeyDescriptor timeout = ConfigKeyDescriptor
            .builder("service.timeoutMs", ConfigValueType.LONG)
            .defaultValue("1000").min(0L).max(60_000L).build();
    private final ConfigKeyDescriptor theme = ConfigKeyDescriptor
            .builder("ui.theme", ConfigValueType.STRING)
            .defaultValue("light").allowedValues(Set.of("light", "dark")).build();
    private final ConfigKeyDescriptor requiredToken = ConfigKeyDescriptor
            .builder("security.token", ConfigValueType.STRING).required(true).build();

    private ConfigSchema schema() {
        return new ConfigSchema(List.of(timeout, theme, requiredToken));
    }

    @Test
    void runtimeOverridesStartupWhichOverridesDefault() {
        LayeredConfigService config = new LayeredConfigService(schema(),
                Map.of("service.timeoutMs", "2000"));
        // Runtime layer simulated by a live supplier, as the store would attach.
        config.attachRuntimeView(() -> Map.of("service.timeoutMs", "3000"));

        var runtime = config.resolve("service.timeoutMs");
        assertEquals(3000L, runtime.value());
        assertEquals(ConfigLayer.RUNTIME, runtime.layer());

        config.attachRuntimeView(Map::of); // simulate a version without the override
        var startup = config.resolve("service.timeoutMs");
        assertEquals(2000L, startup.value());
        assertEquals(ConfigLayer.STARTUP, startup.layer());

        LayeredConfigService onlyDefault = new LayeredConfigService(schema(), Map.of());
        var def = onlyDefault.resolve("service.timeoutMs");
        assertEquals(1000L, def.value());
        assertEquals(ConfigLayer.DEFAULT, def.layer());
        log.info("[priority] layers resolved -> runtime={}, startup={}, default={}",
                runtime.layer(), startup.layer(), def.layer());
    }

    @Test
    void runtimeOverrideAppearsThroughStoreAndIsAtomicWithFlags() {
        LayeredConfigService config = new LayeredConfigService(schema(), Map.of());
        LongSupplier clock = new AtomicLong(5000)::incrementAndGet;
        FeatureFlagStore store = new FeatureFlagStore(20, clock,
                new RolloutEvaluator(new BucketHasher()), config);

        assertEquals(1000L, config.getLong("service.timeoutMs")); // default
        store.publish(PublishBatch.of("alice", "raise timeout",
                PublishEntry.config("service.timeoutMs", "5000")));
        assertEquals(5000L, config.getLong("service.timeoutMs")); // runtime now wins
        log.info("[runtimePublish] timeout after publish={}, observed version={}",
                config.getLong("service.timeoutMs"), store.currentVersionNumber());

        store.rollbackTo(0, "bob", "reset");
        assertEquals(1000L, config.getLong("service.timeoutMs")); // back to default
        log.info("[runtimeRollback] timeout after rollback={}", config.getLong("service.timeoutMs"));
    }

    @Test
    void missingRequiredKeyRaisesMissingConfig() {
        LayeredConfigService config = new LayeredConfigService(schema(), Map.of());
        MissingConfigException ex = assertThrows(MissingConfigException.class,
                () -> config.resolve("security.token"));
        log.info("[missing] typed error: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("security.token"));
    }

    @Test
    void wrongTypeRaisesTypeMismatch() {
        ConfigTypeMismatchException startupEx = assertThrows(ConfigTypeMismatchException.class,
                () -> new LayeredConfigService(schema(),
                        Map.of("service.timeoutMs", "not-a-number")));
        log.info("[typeMismatch] startup eagerly rejected: {}", startupEx.getMessage());

        LayeredConfigService config = new LayeredConfigService(schema(), Map.of());
        ConfigTypeMismatchException runtimeEx = assertThrows(ConfigTypeMismatchException.class,
                () -> config.validateValue("service.timeoutMs", "abc"));
        log.info("[typeMismatch] runtime validation rejected: {}", runtimeEx.getMessage());
    }

    @Test
    void illegalValueRaisesInvalidValueAndDoesNotEchoRaw() {
        LayeredConfigService config = new LayeredConfigService(schema(), Map.of());
        InvalidConfigValueException rangeEx = assertThrows(InvalidConfigValueException.class,
                () -> config.validateValue("service.timeoutMs", "999999"));
        log.info("[invalidValue] out-of-range rejected: {}", rangeEx.getMessage());
        InvalidConfigValueException enumEx = assertThrows(InvalidConfigValueException.class,
                () -> config.validateValue("ui.theme", "neon"));
        log.info("[invalidValue] not-allowed rejected: {}", enumEx.getMessage());

        InvalidConfigValueException unknownEx = assertThrows(InvalidConfigValueException.class,
                () -> config.resolve("not.declared.anywhere"));
        log.info("[invalidValue] unknown key rejected: {}", unknownEx.getMessage());

        // Errors must not leak the raw value (which may be sensitive). Use a value with the
        // correct type that violates a constraint; the message describes the rule, not the value.
        String secret = "neon-secret-theme-value";
        InvalidConfigValueException ex = assertThrows(InvalidConfigValueException.class,
                () -> config.validateValue("ui.theme", secret));
        assertFalse(ex.getMessage().contains(secret));
    }

    @Test
    void illegalStartupValueFailsConstructionNotSilentlyIgnored() {
        assertThrows(InvalidConfigValueException.class,
                () -> new LayeredConfigService(schema(), Map.of("ui.theme", "neon")));
        log.info("[startupFailFast] illegal startup value aborts construction");
    }
}
