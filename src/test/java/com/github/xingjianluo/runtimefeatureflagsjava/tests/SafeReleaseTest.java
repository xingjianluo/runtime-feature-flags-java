package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.ConfigSchema;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.ConfigError;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionConflictException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionNotFoundException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigValueType;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.DryRunReport;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.EvaluationStatus;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.FeatureFlag;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.KeyDiff;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishBatch;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishEntry;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.RolloutRule;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Version;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.VersionDiff;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.BucketHasher;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.RolloutEvaluator;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.store.FeatureFlagStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Safe release" behaviors: read-only dry-run preflight, optimistic-concurrency
 * publish/rollback (expectedVersion), and redacted version diffs.
 * Pure in-memory; no external services or credentials.
 */
class SafeReleaseTest {

    private static final Logger log = LoggerFactory.getLogger(SafeReleaseTest.class);

    private FeatureFlagStore newStore(int historyLimit) {
        AtomicLong tick = new AtomicLong(1000);
        LongSupplier clock = tick::incrementAndGet;
        RolloutEvaluator evaluator = new RolloutEvaluator(new BucketHasher());
        ConfigKeyDescriptor password = ConfigKeyDescriptor
                .builder("db.password", ConfigValueType.STRING).defaultValue("change-me").build();
        ConfigKeyDescriptor retries = ConfigKeyDescriptor
                .builder("service.maxRetries", ConfigValueType.INT)
                .defaultValue("3").min(0L).max(10L).build();
        ConfigKeyDescriptor mode = ConfigKeyDescriptor
                .builder("payment.mode", ConfigValueType.STRING).required(true).build();
        ConfigSchema schema = new ConfigSchema(List.of(password, retries, mode));
        LayeredConfigService configService = new LayeredConfigService(schema, Map.of());
        return new FeatureFlagStore(historyLimit, clock, evaluator, configService);
    }

    private static PublishEntry flagOn(String key) {
        return PublishEntry.flag(FeatureFlag.enabled(key, RolloutRule.allOn()));
    }

    // ---------- dry-run preflight ----------

    @Test
    void dryRunPreviewsPerKeyEffectWithoutTouchingState() {
        FeatureFlagStore store = newStore(20);
        store.publish(PublishBatch.of("alice", "v1",
                flagOn("checkout"), PublishEntry.config("service.maxRetries", "5")));
        long versionBefore = store.currentVersionNumber();
        int historyBefore = store.history().size();
        EvaluationStatus evalBefore = store.evaluate("checkout", "u1").status();

        DryRunReport report = store.dryRun(PublishBatch.of("bob", "preflight",
                flagOn("new-flag"),                                   // added
                flagOn("checkout"),                                   // unchanged
                PublishEntry.config("service.maxRetries", "7"),       // changed
                PublishEntry.config("db.password", "s3cret")));       // added (secret)

        log.info("[safe-release] dry-run report: base={} valid={} changes={}",
                report.baseVersion(), report.valid(), report.changes());
        assertTrue(report.valid());
        assertEquals(versionBefore, report.baseVersion());

        Map<String, KeyDiff> byKey = report.changes().stream()
                .collect(java.util.stream.Collectors.toMap(KeyDiff::key, d -> d));
        assertEquals(KeyDiff.Kind.ADDED, byKey.get("new-flag").kind());
        assertEquals(KeyDiff.Kind.UNCHANGED, byKey.get("checkout").kind());
        assertEquals(KeyDiff.Kind.CHANGED, byKey.get("service.maxRetries").kind());
        assertEquals(KeyDiff.Kind.ADDED, byKey.get("db.password").kind());
        // Redaction: the raw secret must not appear anywhere in the report.
        report.changes().forEach(d -> {
            assertFalse(d.beforeSummary().contains("s3cret"));
            assertFalse(d.afterSummary().contains("s3cret"));
        });
        assertTrue(byKey.get("db.password").afterSummary().startsWith("sha256:"));

        // Pure read: version, history and evaluations are exactly as before.
        assertEquals(versionBefore, store.currentVersionNumber());
        assertEquals(historyBefore, store.history().size());
        assertEquals(evalBefore, store.evaluate("checkout", "u1").status());
        assertEquals(EvaluationStatus.UNKNOWN_FLAG, store.evaluate("new-flag", "u1").status());
        log.info("[safe-release] state untouched by dry-run: version={} history={}",
                store.currentVersionNumber(), store.history().size());
    }

    @Test
    void dryRunCategorizesInvalidEntriesAndStaysReadOnly() {
        FeatureFlagStore store = newStore(20);
        long versionBefore = store.currentVersionNumber();

        DryRunReport report = store.dryRun(PublishBatch.of("bob", "bad batch",
                PublishEntry.config("service.maxRetries", "not-a-number"), // TYPE_MISMATCH
                PublishEntry.config("service.maxRetries", "99"),           // INVALID_VALUE
                PublishEntry.config("no.such.key", "x"),                   // UNKNOWN_KEY
                PublishEntry.config("payment.mode", "")));                 // MISSING (required)

        log.info("[safe-release] dry-run errors: {}", report.errors());
        assertFalse(report.valid());
        List<ConfigError.Type> types = report.errors().stream().map(ConfigError::type).toList();
        assertTrue(types.contains(ConfigError.Type.TYPE_MISMATCH), "expected TYPE_MISMATCH");
        assertTrue(types.contains(ConfigError.Type.INVALID_VALUE), "expected INVALID_VALUE");
        assertTrue(types.contains(ConfigError.Type.UNKNOWN_KEY), "expected UNKNOWN_KEY");
        assertTrue(types.contains(ConfigError.Type.MISSING), "expected MISSING");
        // Errors must not echo raw input values.
        report.errors().forEach(e -> assertFalse(e.detail().contains("not-a-number")));

        assertEquals(versionBefore, store.currentVersionNumber());
        assertTrue(store.current().configValues().isEmpty());
    }

    // ---------- optimistic concurrency ----------

    @Test
    void publishWithMatchingExpectedVersionSucceeds() {
        FeatureFlagStore store = newStore(20);
        Version v1 = store.publish(PublishBatch.of("alice", "v1", flagOn("a")));
        // The typical flow: dry-run to see the base version, then commit pinned to it.
        DryRunReport report = store.dryRun(PublishBatch.of("bob", "p", flagOn("b")));
        assertEquals(v1.versionNumber(), report.baseVersion());
        Version v2 = store.publish(PublishBatch.of("bob", "p", flagOn("b")), report.baseVersion());
        assertEquals(v1.versionNumber() + 1, v2.versionNumber());
        assertEquals(EvaluationStatus.ENABLED, store.evaluate("b", "u1").status());
        log.info("[safe-release] pinned publish ok: v{} -> v{}", v1.versionNumber(), v2.versionNumber());
    }

    @Test
    void publishWithStaleExpectedVersionIsRejectedAtomically() {
        FeatureFlagStore store = newStore(20);
        Version v1 = store.publish(PublishBatch.of("alice", "v1", flagOn("a")));
        // Bob prefetches at v1, Alice moves the world to v2 in between.
        store.publish(PublishBatch.of("alice", "v2", flagOn("a2")));

        PublishBatch bobsBatch = PublishBatch.of("bob", "stale",
                flagOn("b"), PublishEntry.config("service.maxRetries", "7"));
        VersionConflictException conflict = assertThrows(VersionConflictException.class,
                () -> store.publish(bobsBatch, v1.versionNumber()));
        log.info("[safe-release] stale publish rejected: {}", conflict.getMessage());

        // Nothing applied, no partial update, version did not move.
        assertEquals(2, store.currentVersionNumber());
        assertEquals(EvaluationStatus.UNKNOWN_FLAG, store.evaluate("b", "u1").status());
        assertFalse(store.current().configValues().containsKey("service.maxRetries"));
        // Conflict is distinguishable from a validation failure.
        assertNotEquals(com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error
                .InvalidBatchException.class, conflict.getClass());
    }

    @Test
    void publishWithoutExpectedVersionKeepsLegacyBehavior() {
        FeatureFlagStore store = newStore(20);
        store.publish(PublishBatch.of("alice", "v1", flagOn("a")));
        // Null expectedVersion: unconditional publish, exactly as before.
        Version v2 = store.publish(PublishBatch.of("bob", "legacy", flagOn("b")), null);
        assertEquals(2, v2.versionNumber());
        // And the single-arg overload is untouched.
        Version v3 = store.publish(PublishBatch.of("carol", "legacy", flagOn("c")));
        assertEquals(3, v3.versionNumber());
        log.info("[safe-release] legacy unpinned publishes ok: v2, v3");
    }

    @Test
    void rollbackWithExpectedVersionIsCheckedToo() {
        FeatureFlagStore store = newStore(20);
        store.publish(PublishBatch.of("alice", "v1", flagOn("a")));
        Version v2 = store.publish(PublishBatch.of("alice", "v2", flagOn("a2")));

        // Stale pin: rejected, current version untouched.
        assertThrows(VersionConflictException.class,
                () -> store.rollbackTo(0, "bob", "undo", 1L));
        assertEquals(v2.versionNumber(), store.currentVersionNumber());

        // Correct pin: rollback proceeds.
        Version v3 = store.rollbackTo(0, "bob", "undo", v2.versionNumber());
        assertEquals(3, v3.versionNumber());
        assertEquals(EvaluationStatus.ROLLED_BACK, store.evaluate("a", "u1").status());
        // Legacy 3-arg rollback still works.
        Version v4 = store.rollbackTo(v2.versionNumber(), "carol", "redo");
        assertEquals(4, v4.versionNumber());
        log.info("[safe-release] pinned rollback ok: conflict rejected, v{} and v{} committed",
                v3.versionNumber(), v4.versionNumber());
    }

    // ---------- version diff ----------

    @Test
    void diffBetweenRetainedVersionsShowsRedactedKeyChanges() {
        FeatureFlagStore store = newStore(20);
        Version v1 = store.publish(PublishBatch.of("alice", "v1",
                flagOn("keep"), flagOn("drop-me"),
                PublishEntry.config("service.maxRetries", "5"),
                PublishEntry.config("db.password", "first-secret")));
        Version v2 = store.publish(PublishBatch.of("bob", "v2",
                flagOn("keep"),
                PublishEntry.flag(FeatureFlag.enabled("added", RolloutRule.allOn())),
                PublishEntry.config("service.maxRetries", "8"),
                PublishEntry.config("db.password", "second-secret")));
        // v2 does not contain drop-me: publish is upsert on a full snapshot copy,
        // so it is still there; remove it by rolling forward without it is not possible.
        // Instead diff v1 -> v2 for added/changed, and v2 -> v1 for removed/changed.

        VersionDiff forward = store.diff(v1.versionNumber(), v2.versionNumber());
        log.info("[safe-release] diff v{}->v{}: {}", v1.versionNumber(), v2.versionNumber(), forward.changes());
        Map<String, KeyDiff> byKey = forward.changes().stream()
                .collect(java.util.stream.Collectors.toMap(KeyDiff::key, d -> d));
        assertEquals(KeyDiff.Kind.ADDED, byKey.get("added").kind());
        assertEquals(KeyDiff.Kind.CHANGED, byKey.get("service.maxRetries").kind());
        assertEquals(KeyDiff.Kind.CHANGED, byKey.get("db.password").kind());
        assertFalse(byKey.containsKey("keep"), "unchanged keys are omitted");
        // Redacted only: neither secret appears; digests differ.
        KeyDiff password = byKey.get("db.password");
        assertTrue(password.beforeSummary().startsWith("sha256:"));
        assertTrue(password.afterSummary().startsWith("sha256:"));
        forward.changes().forEach(d -> {
            assertFalse(d.beforeSummary().contains("secret"));
            assertFalse(d.afterSummary().contains("secret"));
        });

        VersionDiff backward = store.diff(v2.versionNumber(), v1.versionNumber());
        Map<String, KeyDiff> backByKey = backward.changes().stream()
                .collect(java.util.stream.Collectors.toMap(KeyDiff::key, d -> d));
        assertEquals(KeyDiff.Kind.REMOVED, backByKey.get("added").kind());
        log.info("[safe-release] diff v{}->v{}: {}", v2.versionNumber(), v1.versionNumber(), backward.changes());
    }

    @Test
    void diffWithExpiredVersionIsDistinguishable() {
        FeatureFlagStore store = newStore(3); // tiny history: bootstrap falls off quickly
        for (int i = 0; i < 5; i++) {
            store.publish(PublishBatch.of("alice", "v" + (i + 1), flagOn("f" + i)));
        }
        long current = store.currentVersionNumber();
        // v0 (bootstrap) has been evicted from the retained history.
        VersionNotFoundException expired = assertThrows(VersionNotFoundException.class,
                () -> store.diff(0, current));
        log.info("[safe-release] expired diff rejected: {}", expired.getMessage());
        // Also when the *target* version is gone.
        assertThrows(VersionNotFoundException.class, () -> store.diff(current, 0));
        // A retained range still works.
        VersionDiff ok = store.diff(current - 1, current);
        assertEquals(1, ok.changes().size());
        assertEquals(KeyDiff.Kind.ADDED, ok.changes().get(0).kind());
    }
}
