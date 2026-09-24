package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagEvaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagStatus;
import com.github.xingjianluo.runtimefeatureflagsjava.service.FeatureFlagService;
import com.github.xingjianluo.runtimefeatureflagsjava.service.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.service.VersionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeatureFlagServiceTest {
    private static final Logger log = LoggerFactory.getLogger(FeatureFlagServiceTest.class);

    private FeatureFlagService service;
    private VersionStore store;

    @BeforeEach
    void setUp() {
        var registry = LayeredConfigServiceTest.buildRegistry();
        store = new VersionStore(registry, 20);
        LayeredConfigService config = new LayeredConfigService(registry, Map::of,
                () -> store.current().configValues());
        service = new FeatureFlagService(store, config);
    }

    @Test
    @DisplayName("从未发布的开关返回 UNKNOWN，可与 ROLLED_BACK 区分")
    void unknownVersusRolledBack() {
        FlagEvaluation unknown = service.evaluate("ghost", "u1");
        log.info("输入: flag=ghost(从未发布), subject=u1 -> status={}, 判定依据='{}'",
                unknown.status(), unknown.reason());
        assertEquals(FlagStatus.UNKNOWN, unknown.status());

        service.publishBatch(Map.of(),
                Map.of("ghost", new FlagRule(100, Set.of(), Set.of(), Set.of(), Set.of())),
                "a", "introduce");
        assertEquals(FlagStatus.ON, service.evaluate("ghost", "u1").status());

        // 回滚到初始空版本：开关曾经存在，当前不存在，必须返回 ROLLED_BACK 而非 UNKNOWN
        service.rollback(0L, "a");
        FlagEvaluation rolledBack = service.evaluate("ghost", "u1");
        log.info("输入: flag=ghost(已随回滚消失), subject=u1, version={} -> status={}, 判定依据='{}'",
                rolledBack.version(), rolledBack.status(), rolledBack.reason());
        assertEquals(FlagStatus.ROLLED_BACK, rolledBack.status());
        assertEquals(-1, rolledBack.bucket());
    }

    @Test
    @DisplayName("回滚后再次发布：开关从 ROLLED_BACK 恢复为可判定的 ON/OFF")
    void republishAfterRollback() {
        service.publishBatch(Map.of(),
                Map.of("wave", new FlagRule(0, Set.of(), Set.of(), Set.of(), Set.of())), "a", "v1");
        service.rollback(0L, "a");
        assertEquals(FlagStatus.ROLLED_BACK, service.evaluate("wave", "u1").status());

        service.publishBatch(Map.of(),
                Map.of("wave", new FlagRule(100, Set.of(), Set.of(), Set.of(), Set.of())), "a", "v2");
        FlagEvaluation back = service.evaluate("wave", "u1");
        log.info("输入: flag=wave(重新发布为100%), subject=u1 -> status={}, 判定依据='{}'",
                back.status(), back.reason());
        assertEquals(FlagStatus.ON, back.status());
    }

    @Test
    @DisplayName("发布后配置读取命中运行时层，回滚后退回默认/启动层")
    void configLayerFollowsVersions() {
        // 无任何发布：page-size 命中默认层
        assertEquals("20", service.getConfig("page-size"));
        service.publishBatch(Map.of("page-size", "88"), Map.of(), "a", "runtime");
        var runtimeRead = service.resolvedConfig("page-size");
        log.info("输入: key=page-size, 当前版本={} -> 值={}, 层={}",
                service.currentVersion(), runtimeRead.rawValue(), runtimeRead.layer());
        assertEquals("88", runtimeRead.rawValue());
        assertEquals(LayeredConfigService.Layer.RUNTIME, runtimeRead.layer());

        service.rollback(0L, "a");
        var afterRollback = service.resolvedConfig("page-size");
        log.info("输入: key=page-size, 回滚后版本={} -> 值={}, 层={}",
                service.currentVersion(), afterRollback.rawValue(), afterRollback.layer());
        assertEquals("20", afterRollback.rawValue());
        assertEquals(LayeredConfigService.Layer.DEFAULT, afterRollback.layer());
    }
}
