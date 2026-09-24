package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidBatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionNotFoundException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.FeatureFlag;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishBatch;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishEntry;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.RolloutRule;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Version;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.BucketHasher;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.RolloutEvaluator;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.store.FeatureFlagStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency, atomicity and rollback semantics. No external services or credentials used;
 * a deterministic in-memory clock keeps version numbers and timing predictable.
 */
class StoreConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(StoreConcurrencyTest.class);

    private FeatureFlagStore newStore(int historyLimit) {
        AtomicLong tick = new AtomicLong(1000);
        LongSupplier clock = tick::incrementAndGet;
        RolloutEvaluator evaluator = new RolloutEvaluator(new BucketHasher());
        return new FeatureFlagStore(historyLimit, clock, evaluator, null);
    }

    /** Store backed by a real schema so raw config values are validated at publish time. */
    private FeatureFlagStore newStoreWithConfig(int historyLimit) {
        AtomicLong tick = new AtomicLong(1000);
        LongSupplier clock = tick::incrementAndGet;
        RolloutEvaluator evaluator = new RolloutEvaluator(new BucketHasher());
        com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor password =
                com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor
                        .builder("db.password",
                                com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigValueType.STRING)
                        .defaultValue("change-me").build();
        com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor retries =
                com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor
                        .builder("service.maxRetries",
                                com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigValueType.INT)
                        .defaultValue("3").min(0L).max(10L).build();
        com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.ConfigSchema schema =
                new com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.ConfigSchema(
                        List.of(password, retries));
        var configService = new com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.LayeredConfigService(
                schema, java.util.Map.of());
        return new FeatureFlagStore(historyLimit, clock, evaluator, configService);
    }

    @Test
    void concurrentPublishesAreSerialisedMonotonicAndNeverPartial() throws Exception {
        FeatureFlagStore store = newStore(100);
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> observedVersions = ConcurrentHashMap.newKeySet();
        AtomicLong maxVersion = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String key = "flag-" + threadId + "-" + i;
                        FeatureFlag flag = FeatureFlag.enabled(key, RolloutRule.allOn());
                        Version v = store.publish(PublishBatch.of(
                                "op-" + threadId, "concurrent", PublishEntry.flag(flag)));
                        observedVersions.add(v.versionNumber());
                        maxVersion.accumulateAndGet(v.versionNumber(), Math::max);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "publishes should finish");

        long expected = (long) threads * perThread;
        log.info("[concurrentPublish] threads={} perThread={} expectedFinalVersion={} actualFinalVersion={}",
                threads, perThread, expected, store.currentVersionNumber());

        assertEquals(expected, store.currentVersionNumber(), "every successful publish bumps version once");
        assertEquals((int) expected, observedVersions.size(), "every version number unique (no lost update)");

        // The final snapshot must be complete: all flags present, none half-applied.
        Version current = store.current();
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                String key = "flag-" + t + "-" + i;
                assertTrue(current.flags().containsKey(key),
                        "missing flag => partial update detected: " + key);
            }
        }
        assertEquals((int) expected, current.flags().size(), "final version contains whole batch set");
    }

    @Test
    void concurrentReadersAlwaysObserveAConsistentWholeVersion() throws Exception {
        FeatureFlagStore store = newStore(100);
        // Two flags always published together; a reader must see both or neither per version.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong violations = new AtomicLong();

        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (System.nanoTime() < deadline) {
                        Version v = store.current();
                        boolean hasA = v.flags().containsKey("pair-a");
                        boolean hasB = v.flags().containsKey("pair-b");
                        if (hasA != hasB) {
                            violations.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    FeatureFlag a = FeatureFlag.enabled("pair-a", RolloutRule.allOn());
                    FeatureFlag b = FeatureFlag.enabled("pair-b", RolloutRule.allOn());
                    store.publish(PublishBatch.of("writer", "pair",
                            PublishEntry.flag(a), PublishEntry.flag(b)));
                    store.rollbackTo(0, "writer", "restore bootstrap");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("[consistentReads] observed torn-read violations={} (expected 0)", violations.get());
        assertEquals(0, violations.get(), "a reader must never observe a partially applied batch");
    }

    @Test
    void invalidBatchIsRejectedWholesaleWithNoPartialUpdate() {
        FeatureFlagStore store = newStoreWithConfig(20);
        // First a valid publish to establish v1.
        FeatureFlag good = FeatureFlag.enabled("good-flag", RolloutRule.allOn());
        store.publish(PublishBatch.of("alice", "seed", PublishEntry.flag(good)));
        assertEquals(1, store.currentVersionNumber());

        // Mixed batch validated inside the store: a valid flag sibling plus an illegal raw
        // config value (maxRetries constrained to <=10). The whole batch must be rejected.
        FeatureFlag other = FeatureFlag.enabled("other-flag", RolloutRule.allOn());
        PublishBatch badBatch = new PublishBatch(
                List.of(PublishEntry.flag(other), PublishEntry.config("service.maxRetries", "999")),
                "alice", "should fail");

        InvalidBatchException ex = assertThrows(InvalidBatchException.class, () -> store.publish(badBatch));
        log.info("[invalidBatch] rejected as expected: {}", ex.getMessage());

        assertEquals(1, store.currentVersionNumber(), "version must not advance on rejected batch");
        Version current = store.current();
        assertTrue(current.flags().containsKey("good-flag"));
        assertFalse(current.flags().containsKey("other-flag"), "valid sibling must NOT be partially applied");
        assertFalse(current.configValues().containsKey("service.maxRetries"));
    }

    @Test
    void illegalFlagArgumentsAreRejectedBeforeAnyBatchCommit() {
        // RolloutRule is an invariant-bearing value type, so out-of-range/conflicting rules
        // fail when the entry is built, before a batch can even reach the store.
        assertThrows(IllegalArgumentException.class,
                () -> new RolloutRule(150, Set.of(), Set.of(), false, false));
        FeatureFlagStore store = newStore(20);
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureFlag("conflicted", true,
                        new RolloutRule(10, Set.of(), Set.of(), true, true), List.of()));
        log.info("[illegalFlagArgs] percentage>100 and force conflict rejected at construction");
        assertEquals(0, store.currentVersionNumber());
    }

    @Test
    void rollbackRestoresWholeStateAndRemovedFlagsReportRolledBack() {
        FeatureFlagStore store = newStore(20);
        FeatureFlag flagA = FeatureFlag.enabled("flag-a", RolloutRule.allOn());
        Version v1 = store.publish(PublishBatch.of("alice", "add a", PublishEntry.flag(flagA)));
        FeatureFlag flagB = FeatureFlag.enabled("flag-b", RolloutRule.allOn());
        Version v2 = store.publish(PublishBatch.of("alice", "add b", PublishEntry.flag(flagB)));
        log.info("[rollback] before: v{} flags={}", store.currentVersionNumber(),
                new HashSet<>(store.current().flags().keySet()));

        Version restored = store.rollbackTo(v1.versionNumber(), "carol", "undo b");
        log.info("[rollback] after: v{} flags={} reason='{}'", restored.versionNumber(),
                new HashSet<>(restored.flags().keySet()), restored.reason());

        assertEquals(3, restored.versionNumber(), "rollback creates a new version, never rewrites history");
        assertTrue(restored.flags().containsKey("flag-a"));
        assertFalse(restored.flags().containsKey("flag-b"), "rollback must remove b whole");

        // Distinguishable statuses for unknown vs rolled-back flags.
        var evalB = store.evaluate("flag-b", "user-1");
        var evalUnknown = store.evaluate("never-existed", "user-1");
        log.info("[rollback] flag-b status={}, never-existed status={}",
                evalB.status(), evalUnknown.status());
        assertEquals(com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.EvaluationStatus.ROLLED_BACK,
                evalB.status());
        assertEquals(com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.EvaluationStatus.UNKNOWN_FLAG,
                evalUnknown.status());

        // The rollback itself is auditable and reversible.
        Version backAgain = store.rollbackTo(v2.versionNumber(), "carol", "redo b");
        assertTrue(backAgain.flags().containsKey("flag-b"));
    }

    @Test
    void rollingBackToExpiredVersionFailsClearly() {
        FeatureFlagStore store = newStore(3);
        store.publish(PublishBatch.of("a", "v1",
                PublishEntry.flag(FeatureFlag.enabled("f1", RolloutRule.allOn()))));
        store.publish(PublishBatch.of("a", "v2",
                PublishEntry.flag(FeatureFlag.enabled("f2", RolloutRule.allOn()))));
        store.publish(PublishBatch.of("a", "v3",
                PublishEntry.flag(FeatureFlag.enabled("f3", RolloutRule.allOn()))));
        // historyLimit 3: bootstrap(v0) has now expired.
        VersionNotFoundException ex = assertThrows(VersionNotFoundException.class,
                () -> store.rollbackTo(0, "a", "too old"));
        log.info("[expiredVersion] rejected: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void changeRecordsDoNotLeakValues() {
        FeatureFlagStore store = newStoreWithConfig(20);
        store.publish(PublishBatch.of("a", "secret cfg",
                PublishEntry.config("db.password", "super-secret-value-123")));
        var records = store.changeRecordsFor("db.password");
        assertEquals(1, records.size());
        String redacted = records.get(0).redactedValue();
        log.info("[audit] stored redacted value='{}' (raw must not appear)", redacted);
        assertNotEquals("super-secret-value-123", redacted);
        assertFalse(redacted.contains("super-secret"));
        assertTrue(redacted.startsWith("sha256:"));
    }
}
