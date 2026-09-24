package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.config.InMemoryConfigRegistry;
import com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.MissingConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.TypeMismatchConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.UnknownConfigKeyException;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigDefinition;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigRegistry;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigType;
import com.github.xingjianluo.runtimefeatureflagsjava.service.LayeredConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredConfigServiceTest {
    private static final Logger log = LoggerFactory.getLogger(LayeredConfigServiceTest.class);

    private ConfigRegistry registry;

    static ConfigRegistry buildRegistry() {
        InMemoryConfigRegistry r = new InMemoryConfigRegistry();
        r.register(new ConfigDefinition("page-size", ConfigType.INTEGER, "分页", "20", 1L, 200L, false));
        r.register(new ConfigDefinition("timeout", ConfigType.LONG, "超时", null, 1L, 60000L, false));
        r.register(new ConfigDefinition("dark", ConfigType.BOOLEAN, "深色", "false", null, null, false));
        r.register(new ConfigDefinition("secret", ConfigType.STRING, "密钥", null, null, null, true));
        return r.freeze();
    }

    @BeforeEach
    void setUp() {
        registry = buildRegistry();
    }

    private LayeredConfigService service(Map<String, String> startup, Map<String, String> runtime) {
        Map<String, String> boot = Map.copyOf(startup);
        Map<String, String> rt = Map.copyOf(runtime);
        return new LayeredConfigService(registry, () -> boot, () -> rt);
    }

    @Test
    @DisplayName("优先级：运行时覆盖 > 启动配置 > 默认值；生效层可解释")
    void layeredPrecedence() {
        LayeredConfigService onlyDefault = service(Map.of(), Map.of());
        var d = onlyDefault.resolve("page-size");
        log.info("输入: key=page-size, 启动层无, 运行时无 -> 值={}, 判定依据层={}({})",
                d.rawValue(), d.layer().displayName(), d.layer().precedence());
        assertEquals("20", d.rawValue());
        assertEquals(LayeredConfigService.Layer.DEFAULT, d.layer());

        LayeredConfigService withStartup = service(Map.of("page-size", "50"), Map.of());
        var s = withStartup.resolve("page-size");
        log.info("输入: key=page-size, 启动层=50 -> 值={}, 判定依据层={}", s.rawValue(), s.layer());
        assertEquals("50", s.rawValue());
        assertEquals(LayeredConfigService.Layer.STARTUP, s.layer());

        LayeredConfigService withRuntime = service(Map.of("page-size", "50"), Map.of("page-size", "100"));
        var r = withRuntime.resolve("page-size");
        log.info("输入: key=page-size, 启动层=50, 运行时=100 -> 值={}, 判定依据层={}", r.rawValue(), r.layer());
        assertEquals("100", r.rawValue());
        assertEquals(LayeredConfigService.Layer.RUNTIME, r.layer());
    }

    @Test
    @DisplayName("三层均缺失抛 MissingConfigException，不静默回退为空串")
    void missingIsRejected() {
        LayeredConfigService svc = service(Map.of(), Map.of());
        MissingConfigException ex = assertThrows(MissingConfigException.class, () -> svc.resolve("timeout"));
        log.warn("输入: key=timeout（无默认/启动/运行时） -> 可区分错误: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("timeout"));
    }

    @Test
    @DisplayName("未注册键抛 UnknownConfigKeyException")
    void unknownKeyRejected() {
        LayeredConfigService svc = service(Map.of(), Map.of());
        assertThrows(UnknownConfigKeyException.class, () -> svc.resolve("not-defined"));
        // 启动层出现未知键同样快速失败
        assertThrows(UnknownConfigKeyException.class,
                () -> service(Map.of("not-defined", "1"), Map.of()));
    }

    @Test
    @DisplayName("类型不符抛 TypeMismatchConfigException，与取值非法区分；启动层构造期即快速失败")
    void typeMismatchRejected() {
        TypeMismatchConfigException atStartup = assertThrows(TypeMismatchConfigException.class,
                () -> service(Map.of("page-size", "abc"), Map.of()));
        log.warn("输入: key=page-size, 启动值=abc -> 构造期可区分错误类型=TypeMismatch: {}",
                atStartup.getMessage());
        // 布尔类型非法字面量同样归类为类型不符（运行时层在读取时暴露）
        Map<String, String> runtime = new HashMap<>();
        runtime.put("dark", "maybe");
        LayeredConfigService svc = service(Map.of(), runtime);
        assertThrows(TypeMismatchConfigException.class, () -> svc.resolve("dark"));
    }

    @Test
    @DisplayName("越界取值抛 InvalidValueConfigException（默认值/启动/运行时三层均强制）")
    void outOfRangeRejected() {
        assertThrows(InvalidValueConfigException.class,
                () -> service(Map.of("page-size", "9999"), Map.of()).resolve("page-size"));
        Map<String, String> badRuntime = new HashMap<>();
        badRuntime.put("page-size", "0");
        InvalidValueConfigException ex = assertThrows(InvalidValueConfigException.class,
                () -> service(Map.of(), badRuntime).resolve("page-size"));
        log.warn("输入: key=page-size, 运行时值=0, 下界=1 -> 可区分错误类型=InvalidValue: {}", ex.getMessage());
    }
}
