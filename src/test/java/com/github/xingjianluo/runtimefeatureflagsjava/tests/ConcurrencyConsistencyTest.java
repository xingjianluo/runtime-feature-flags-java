package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.error.BatchValidationException;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagEvaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagStatus;
import com.github.xingjianluo.runtimefeatureflagsjava.service.FeatureFlagService;
import com.github.xingjianluo.runtimefeatureflagsjava.service.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.service.VersionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发语义测试（不依赖任何外部服务或真实凭据）：
 * <ol>
 *   <li>并发发布/回滚串行化提交，版本号严格连续，读取永不跨版本撕裂；</li>
 *   <li>并发读取同一主体结果稳定，不出现漂移；</li>
 *   <li>非法批次混入并发流时绝不产生部分生效。</li>
 * </ol>
 */
class ConcurrencyConsistencyTest {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConsistencyTest.class);

    private FeatureFlagService newService(VersionStore store) {
        var registry = LayeredConfigServiceTest.buildRegistry();
        LayeredConfigService config = new LayeredConfigService(registry, Map::of,
                () -> store.current().configValues());
        return new FeatureFlagService(store, config);
    }

    @Test
    @DisplayName("并发发布/回滚：版本号严格连续递增，任何时刻读取到的版本都完整存在")
    void concurrentPublishesAreSerialized() throws Exception {
        VersionStore store = new VersionStore(LayeredConfigServiceTest.buildRegistry(), 50);
        FeatureFlagService service = newService(store);
        service.publishBatch(Map.of("page-size", "25"),
                Map.of("f", new FlagRule(100, Set.of(), Set.of(), Set.of(), Set.of())),
                "init", "v1");

        int writers = 8;
        int perWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger batchRejected = new AtomicInteger();
        ConcurrentLinkedQueue<Long> committedVersions = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> inconsistencies = new ConcurrentLinkedQueue<>();

        for (int w = 0; w < writers; w++) {
            final int writer = w;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perWriter; i++) {
                    try {
                        int size = 20 + (writer * 7 + i) % 100; // 始终在 [20,119] 内，合法
                        long v;
                        if (i % 5 == 4) {
                            // 周期性回滚到仍在窗口内的较早版本
                            v = service.rollback(1L, "writer-" + writer);
                        } else {
                            v = service.publishBatch(Map.of("page-size", String.valueOf(size)),
                                    Map.of("f", new FlagRule(100, Set.of(), Set.of(), Set.of(), Set.of())),
                                    "writer-" + writer, "n" + i);
                        }
                        committedVersions.add(v);
                    } catch (BatchValidationException e) {
                        batchRejected.incrementAndGet();
                    }
                }
            });
        }

        // 并发读取线程：每个观测都必须对应一个真实存在、内容完整的版本
        AtomicInteger observations = new AtomicInteger();
        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (observations.incrementAndGet() <= 20_000) {
                    // 一次观测只持有一个不可变快照：配置与开关严格同版本
                    var version = service.snapshot();
                    long currentVersion = version.version();
                    String pageSize = service.resolveOn(version, "page-size").rawValue();
                    FlagEvaluation evaluation = service.evaluateOn(version, "f", "reader-subject");
                    // 一致性断言 1：配置值必须能按整数解析且在声明范围内
                    try {
                        int parsed = Integer.parseInt(pageSize);
                        if (parsed < 1 || parsed > 200) {
                            inconsistencies.add("越界值 page-size=" + parsed + " @v" + currentVersion);
                        }
                    } catch (NumberFormatException e) {
                        inconsistencies.add("撕裂读取 page-size=" + pageSize + " @v" + currentVersion);
                    }
                    // 一致性断言 2：版本号 >=1，且 f 在当前版本中时状态只能是 ON/OFF
                    if (currentVersion < 1) {
                        inconsistencies.add("读到不可能的版本号 " + currentVersion);
                    }
                    if (evaluation.status() != FlagStatus.ON && evaluation.status() != FlagStatus.OFF
                            && currentVersion >= 1) {
                        inconsistencies.add("开关状态撕裂: " + evaluation.status() + " @v" + currentVersion);
                    }
                    if (evaluation.version() != currentVersion) {
                        inconsistencies.add("跨版本读取: flag@v" + evaluation.version()
                                + " vs config@v" + currentVersion);
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前结束");

        log.info("并发结果: 提交版本数={}, 整批拒绝数={}, 观测数约={}",
                committedVersions.size(), batchRejected.get(), observations.get());
        assertTrue(inconsistencies.isEmpty(), "出现不一致读取: " + inconsistencies.stream().limit(5).toList());

        // 版本号严格连续：每次成功提交恰好 +1（回滚同样产生新版本）
        long expectedMax = 1L + committedVersions.size();
        assertEquals(expectedMax, store.current().version(),
                "成功提交数与版本号必须一一对应，不得丢失或跳号");
    }

    @Test
    @DisplayName("并发非法批次绝不部分生效，合法读取不受影响且结果不漂移")
    void invalidBatchesNeverPartiallyApply() throws Exception {
        VersionStore store = new VersionStore(LayeredConfigServiceTest.buildRegistry(), 30);
        FeatureFlagService service = newService(store);
        service.publishBatch(Map.of("page-size", "30"),
                Map.of("f", new FlagRule(50, Set.of(), Set.of(), Set.of(), Set.of())),
                "init", "baseline");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<String> drift = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    // 一半线程持续提交非法内容：越界配置 + 非法百分比（构造期拒绝）
                    for (int i = 0; i < 50; i++) {
                        try {
                            if (idx % 2 == 0) {
                                FlagRule illegal = new FlagRule(150, Set.of(), Set.of(), Set.of(), Set.of());
                                service.publishBatch(Map.of("page-size", "999999"),
                                        Map.of("f", illegal), "bad", "invalid");
                            } else {
                                // 合法批次：改为稳定值，不应被非法批次污染
                                service.publishBatch(Map.of("page-size", "30"),
                                        Map.of("f", new FlagRule(50, Set.of(), Set.of(), Set.of(), Set.of())),
                                        "good", "valid");
                            }
                        } catch (BatchValidationException
                                 | com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException e) {
                            // 非法内容在批次校验期或规则构造期被拒绝，二者都必须不生效
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 读取线程：非法批次期间，合法值必须始终为 30，判定结果对同一主体恒定
        FlagStatus stable = service.evaluate("f", "fixed-subject").status();
        pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < 5000; i++) {
                var v = service.snapshot();
                if (!"30".equals(service.resolveOn(v, "page-size").rawValue())) {
                    drift.add("配置被非法批次部分污染: " + service.resolveOn(v, "page-size").rawValue());
                }
                if (service.evaluateOn(v, "f", "fixed-subject").status() != stable) {
                    drift.add("同一主体判定漂移");
                }
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("非法批次压力: 整批拒绝数={}, 漂移观测={}", rejected.get(), drift.size());
        assertTrue(rejected.get() > 0, "应有非法批次被拒绝");
        assertTrue(drift.isEmpty(), "出现部分生效或结果漂移: " + drift.stream().limit(5).toList());
        assertEquals("30", service.getConfig("page-size"));
    }
}
