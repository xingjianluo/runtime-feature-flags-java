package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.error.BatchValidationException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ChangeRecord;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigVersion;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;
import com.github.xingjianluo.runtimefeatureflagsjava.service.VersionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionStoreTest {
    private static final Logger log = LoggerFactory.getLogger(VersionStoreTest.class);

    private VersionStore newStore(int limit) {
        return new VersionStore(LayeredConfigServiceTest.buildRegistry(), limit);
    }

    @Test
    @DisplayName("整批原子发布：配置与开关在同一新版本中可见，版本号严格递增")
    void atomicPublish() {
        VersionStore store = newStore(10);
        ConfigVersion v1 = store.publish(
                Map.of("page-size", "30"),
                Map.of("beta", new FlagRule(100, Set.of(), Set.of(), Set.of(), Set.of())),
                "alice", "first");
        assertEquals(1L, v1.version());
        assertEquals("30", store.current().configValues().get("page-size"));
        assertTrue(store.current().flags().containsKey("beta"));
        log.info("发布输入: configs={{page-size:30}}, flags={{beta:100%}} -> 原子版本={}", v1.version());

        ConfigVersion v2 = store.publish(Map.of("page-size", "40"), Map.of(), "bob", "second");
        assertEquals(2L, v2.version());
        // 未在批次中出现的开关视为整批内容的一部分：当前快照以批次为准
        assertFalse(store.current().flags().containsKey("beta"));
        log.info("发布输入: configs={{page-size:40}}, flags={} -> 版本={}，快照完整反映整批", v2.version());
    }

    @Test
    @DisplayName("非法批次整批拒绝：任一条目失败则无任何部分生效")
    void invalidBatchRejectsAtomically() {
        VersionStore store = newStore(10);
        store.publish(Map.of("page-size", "30"),
                Map.of("good", new FlagRule(10, Set.of(), Set.of(), Set.of(), Set.of())),
                "alice", "baseline");

        // 批次中混有：未注册配置键、越界值、互斥名单；三者都应被收集
        Map<String, String> badConfigs = Map.of("page-size", "9999", "unknown-key", "1");
        Map<String, FlagRule> badFlags = Map.of("bad",
                new FlagRule(50, Set.of(), Set.of(), Set.of("x"), Set.of("x")));
        BatchValidationException ex = assertThrows(BatchValidationException.class,
                () -> store.publish(badConfigs, badFlags, "mallory", "evil batch"));
        log.warn("非法批次输入: 越界+未知键+互斥名单 -> 聚合错误数={}, 错误={}",
                ex.getErrors().size(),
                ex.getErrors().stream().map(Throwable::getMessage).toList());
        assertTrue(ex.getErrors().size() >= 3, "每个错误条目都应被独立收集");

        // 关键断言：当前版本完全保持在发布前状态，没有部分生效
        assertEquals(1L, store.current().version());
        assertEquals("30", store.current().configValues().get("page-size"));
        assertTrue(store.current().flags().containsKey("good"));
        assertFalse(store.current().flags().containsKey("bad"));
    }

    @Test
    @DisplayName("回滚生成新版本并完整恢复内容；再发布不影响回滚语义")
    void rollbackRestoresSnapshot() {
        VersionStore store = newStore(10);
        store.publish(Map.of("page-size", "30"), Map.of("f1", new FlagRule(10, Set.of(), Set.of(), Set.of(), Set.of())), "a", "v1");
        store.publish(Map.of("page-size", "60"), Map.of("f2", new FlagRule(20, Set.of(), Set.of(), Set.of(), Set.of())), "a", "v2");
        ConfigVersion restored = store.rollbackTo(1L, "a");
        log.info("回滚输入: 目标版本=1 -> 新版本={}, page-size={}, flags={}",
                restored.version(), restored.configValues().get("page-size"), restored.flags().keySet());
        assertEquals(3L, restored.version());
        assertEquals("30", restored.configValues().get("page-size"));
        assertTrue(restored.flags().containsKey("f1"));
        assertFalse(restored.flags().containsKey("f2"));

        assertThrows(InvalidValueConfigException.class, () -> store.rollbackTo(999L, "a"));
        // 可以回滚到初始空版本 0（内容为空），会产生新版本号
        ConfigVersion empty = store.rollbackTo(0L, "a");
        assertEquals(4L, empty.version());
        assertTrue(empty.configValues().isEmpty());
    }

    @Test
    @DisplayName("历史按有限条数保留，超出窗口被裁剪")
    void historyIsBounded() {
        VersionStore store = newStore(3);
        for (int i = 0; i < 5; i++) {
            store.publish(Map.of("page-size", String.valueOf(10 + i)), Map.of(), "a", "n" + i);
        }
        List<ConfigVersion> history = store.history();
        log.info("输入: 连续发布5次, limit=3 -> 保留版本={}",
                history.stream().map(ConfigVersion::version).toList());
        assertEquals(3, history.size());
        assertEquals(List.of(5L, 4L, 3L), history.stream().map(ConfigVersion::version).toList());
    }

    @Test
    @DisplayName("变更记录可查询且敏感配置不泄露原值；名单主体不出现在记录中")
    void changeHistoryMasksSensitiveValues() {
        VersionStore store = newStore(10);
        store.publish(Map.of("secret", "super-secret-token"), Map.of(), "a", "set");
        store.publish(Map.of("secret", "rotated-token"), Map.of(), "a", "rotate");
        List<ChangeRecord> records = store.changeHistory("secret");
        log.info("变更查询: key=secret -> 记录数={}", records.size());
        for (ChangeRecord r : records) {
            String text = r.oldValueMasked() + "|" + r.newValueMasked();
            assertFalse(text.contains("super-secret-token"), "敏感旧值不得泄露: " + text);
            assertFalse(text.contains("rotated-token"), "敏感新值不得泄露: " + text);
            log.info("  脱敏记录: old='{}', new='{}', op={}", r.oldValueMasked(), r.newValueMasked(), r.operation());
        }
        store.publish(Map.of(),
                Map.of("named", new FlagRule(10, Set.of("alice"), Set.of("bob"), Set.of(), Set.of())),
                "a", "flag");
        List<ChangeRecord> flagRecords = store.changeHistory("named");
        assertEquals(1, flagRecords.size());
        String joined = String.valueOf(flagRecords.get(0).newValueMasked());
        assertFalse(joined.contains("alice"));
        assertFalse(joined.contains("bob"));
        log.info("开关变更记录仅含摘要: '{}'", joined);
    }
}
