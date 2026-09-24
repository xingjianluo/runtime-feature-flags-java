package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigVersion;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagEvaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagStatus;
import com.github.xingjianluo.runtimefeatureflagsjava.service.BucketHasher;
import com.github.xingjianluo.runtimefeatureflagsjava.service.RolloutEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolloutEvaluatorTest {
    private static final Logger log = LoggerFactory.getLogger(RolloutEvaluatorTest.class);
    private final RolloutEvaluator evaluator = new RolloutEvaluator();

    private ConfigVersion versionWith(Map<String, FlagRule> flags) {
        return new ConfigVersion(7L, java.time.Instant.now(), "tester", "test", Map.of(), flags,
                java.util.List.of());
    }

    @Test
    @DisplayName("forceOn/forceOff 优先级最高，且 forceOn 压过 deny/百分比")
    void forceOverridesWin() {
        FlagRule rule = new FlagRule(0, Set.of(), Set.of("u1"), Set.of("u1"), Set.of());
        ConfigVersion v = versionWith(Map.of("f", rule));
        FlagEvaluation e = evaluator.evaluate(v, "f", "u1");
        log.info("输入: flag=f, subject=u1, percent=0, forceOn=u1, deny=u1 -> status={}, 判定依据='{}'",
                e.status(), e.reason());
        assertEquals(FlagStatus.ON, e.status());
        assertTrue(e.reason().contains("forceOn"));
    }

    @Test
    @DisplayName("denyList 优先于 allowList 之外的路径；allowList 命中即 ON")
    void listsWork() {
        FlagRule rule = new FlagRule(0, Set.of("allow-me"), Set.of("deny-me"), Set.of(), Set.of());
        ConfigVersion v = versionWith(Map.of("f", rule));
        FlagEvaluation deny = evaluator.evaluate(v, "f", "deny-me");
        FlagEvaluation allow = evaluator.evaluate(v, "f", "allow-me");
        log.info("输入: subject=deny-me -> {}, 判定依据='{}'", deny.status(), deny.reason());
        log.info("输入: subject=allow-me -> {}, 判定依据='{}'", allow.status(), allow.reason());
        assertEquals(FlagStatus.OFF, deny.status());
        assertEquals(FlagStatus.ON, allow.status());
    }

    @Test
    @DisplayName("百分比边界：percent=100 全量 ON，percent=0 全量 OFF，bucket<percent 判定")
    void percentBoundaries() {
        ConfigVersion allOn = versionWith(Map.of("f", new FlagRule(100, Set.of(), Set.of(), Set.of(), Set.of())));
        ConfigVersion allOff = versionWith(Map.of("f", new FlagRule(0, Set.of(), Set.of(), Set.of(), Set.of())));
        for (int i = 0; i < 200; i++) {
            String subject = "boundary-user-" + i;
            assertEquals(FlagStatus.ON, evaluator.evaluate(allOn, "f", subject).status());
            assertEquals(FlagStatus.OFF, evaluator.evaluate(allOff, "f", subject).status());
        }
        log.info("输入: percent=100 与 percent=0，各 200 主体 -> 边界全部符合预期");

        // 精确边界：找出 bucket 恰为 50 的主体，验证 percent=50 时它落在 OFF（bucket < percent 才 ON）
        String boundary = null;
        for (int i = 0; i < 1_000_000 && boundary == null; i++) {
            String candidate = "edge-" + i;
            if (BucketHasher.bucket("f", candidate) == 50) {
                boundary = candidate;
            }
        }
        assertTrue(boundary != null, "应能找到 bucket=50 的主体");
        ConfigVersion half = versionWith(Map.of("f", new FlagRule(50, Set.of(), Set.of(), Set.of(), Set.of())));
        FlagEvaluation e = evaluator.evaluate(half, "f", boundary);
        log.info("输入: flag=f, subject={}, bucket=50, percent=50 -> status={}, 判定依据='{}'",
                boundary, e.status(), e.reason());
        assertEquals(50, e.bucket());
        assertEquals(FlagStatus.OFF, e.status(), "bucket == percent 时不得命中");
    }

    @Test
    @DisplayName("未知开关在版本内返回 UNKNOWN，且桶位为 -1")
    void unknownFlag() {
        ConfigVersion v = versionWith(Map.of());
        FlagEvaluation e = evaluator.evaluate(v, "never-seen", "u1");
        log.info("输入: flag=never-seen, subject=u1, version=7 -> status={}, 判定依据='{}'",
                e.status(), e.reason());
        assertEquals(FlagStatus.UNKNOWN, e.status());
        assertEquals(-1, e.bucket());
        assertEquals(7L, e.version());
    }

    @Test
    @DisplayName("同一主体在同一版本上重复判定结果永不漂移")
    void evaluationIsStable() {
        FlagRule rule = new FlagRule(33, Set.of(), Set.of(), Set.of(), Set.of());
        ConfigVersion v = versionWith(Map.of("f", rule));
        FlagEvaluation first = evaluator.evaluate(v, "f", "stable-user");
        for (int i = 0; i < 50; i++) {
            assertEquals(first.status(), evaluator.evaluate(v, "f", "stable-user").status());
        }
        log.info("输入: flag=f, subject=stable-user, percent=33 -> 稳定 status={}, bucket={}",
                first.status(), first.bucket());
    }
}
