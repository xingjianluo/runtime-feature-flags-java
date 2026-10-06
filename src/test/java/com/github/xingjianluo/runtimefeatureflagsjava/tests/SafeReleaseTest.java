package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.ConfigSchema;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidBatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionConflictException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionNotFoundException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigKeyDescriptor;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.ConfigValueType;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Evaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.FeatureFlag;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.KeyChange;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.KeyChangeKind;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PreflightIssueCategory;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PreflightReport;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishBatch;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishEntry;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.RolloutRule;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safe-release behaviors: read-only preflight, optimistic-concurrency publish/rollback,
 * and redacted version diffs. In-memory only; no external services or credentials.
 */
class SafeReleaseTest {

    private static final Logger log = LoggerFactory.getLogger(SafeReleaseTest.class);

    private FeatureFlagStore newStore(int historyLimit) {
        AtomicLong tick = new AtomicLong(1000);
        LongSupplier clock = tick::incrementAndGet;
        RolloutEvaluator evaluator = new RolloutEvaluator(new BucketHasher());
        ConfigSchema schema = new ConfigSchema(List.of(
                ConfigKeyDescriptor.builder("service.maxRetries", ConfigValueType.INT)
                        .defaultValue("3").min(0L).max(10L).build(),
                ConfigKeyDescriptor.builder("db.password", ConfigValueType.STRING)
                        .required(true).build()));
        LayeredConfigService configService = new LayeredConfigService(schema, Map.of());
        return new FeatureFlagStore(historyLimit, clock, evaluator, configService);
    }

    private static FeatureFlag flag(String key, int percentage) {
        return new FeatureFlag(key, true, new RolloutRule(percentage,
                java.util.Set.of(), java.util.Set.of(), false, false), List.of());
    }

    private static Map<String, KeyChange> byKey(List<KeyChange> changes) {
        return changes.stream().collect(Collectors.toMap(KeyChange::key, c -> c));
    }

    @Test
    void preflightPreviewsPerKeyChangesWithoutTouchingState() {
        FeatureFlagStore store = newStore(20);
        store.publish(PublishBatch.of("alice", "v1",
                PublishEntry.flag(flag("checkout", 100)),
                PublishEntry.config("service.maxRetries", "5")));
        Evaluation before = store.evaluate("checkout", "u1");
        int historySize = store.history().size();
        log.info("[preflight] base=v{} evaluation before={}({})",
                before.observedVersion(), before.status(), before.enabled());

        PreflightReport report = store.preflight(PublishBatch.of("bob", "candidate",
                PublishEntry.flag(flag("checkout", 50)),          // overwrite existing flag
                PublishEntry.flag(flag("new-flag", 10)),          // add new flag
                PublishEntry.config("service.maxRetries", "5"),   // identical -> unchanged
                PublishEntry.config("db.password", "s3cret-value"))); // add new config
        log.info("[preflight] report baseVersion={} valid={} changes={}",
                report.baseVersion(), report.valid(), report.changes());

        assertTrue(report.valid());
        assertEquals(1L, report.baseVersion());
        Map<String, KeyChange> changes = byKey(report.changes());
        assertEquals(KeyChangeKind.OVERWRITTEN, changes.get("checkout").kind());
        assertEquals(KeyChangeKind.ADDED, changes.get("new-flag").kind());
        assertEquals(KeyChangeKind.UNCHANGED, changes.get("service.maxRetries").kind());
        assertEquals(KeyChangeKind.ADDED, changes.get("db.password").kind());
        assertEquals("absent", changes.get("db.password").beforeDigest());
        assertTrue(changes.get("db.password").afterDigest().startsWith("sha256:"));

        // Read-only: version, history, evaluation and runtime config all unchanged.
        assertEquals(1L, store.currentVersionNumber());
        assertEquals(historySize, store.history().size());
        Evaluation after = store.evaluate("checkout", "u1");
        assertEquals(before.enabled(), after.enabled());
        assertEquals(before.status(), after.status());
        assertFalse(store.current().configValues().containsKey("db.password"));
        assertEquals(100, store.current().flags().get("checkout").rule().percentage());
        // No raw secret leaks through the report.
        assertFalse(report.toString().contains("s3cret-value"));
        log.info("[preflight] state untouched: version={} checkout pct={}",
                store.currentVersionNumber(),
                store.current().flags().get("checkout").rule().percentage());
    }

    @Test
    void preflightCategorizesInvalidEntriesByKind() {
        FeatureFlagStore store = newStore(20);
        PreflightReport report = store.preflight(PublishBatch.of("bob", "bad candidate",
                PublishEntry.config("service.maxRetries", "abc"),  // not a number
                PublishEntry.config("service.maxRetries", "99"),   // above max
                PublishEntry.config("no.such.key", "x"),           // undeclared key
                PublishEntry.config("db.password", "")));          // required but empty
        log.info("[preflight] invalid report valid={} issues={}", report.valid(), report.issues());

        assertFalse(report.valid());
        assertEquals(4, report.issues().size());
        Map<PreflightIssueCategory, String> byCategory = report.issues().stream()
                .collect(Collectors.toMap(i -> i.category(), i -> i.key()));
        assertEquals("service.maxRetries", byCategory.get(PreflightIssueCategory.TYPE_MISMATCH));
        assertEquals("service.maxRetries", byCategory.get(PreflightIssueCategory.CONSTRAINT_VIOLATION));
        assertEquals("no.such.key", byCategory.get(PreflightIssueCategory.UNKNOWN_CONFIG_KEY));
        assertEquals("db.password", byCategory.get(PreflightIssueCategory.MISSING_REQUIRED_VALUE));

        // A failed preflight changes nothing either.
        assertEquals(0L, store.currentVersionNumber());
        assertTrue(store.current().configValues().isEmpty());
    }

    @Test
    void publishPinnedToStaleBaseVersionIsRejectedAsConflict() {
        FeatureFlagStore store = newStore(20);
        store.publish(PublishBatch.of("alice", "v1", PublishEntry.flag(flag("checkout", 100))));
        long seenAtPreflight = store.currentVersionNumber(); // what the second operator based on

        // Someone else commits in between.
        store.publish(PublishBatch.of("carol", "v2",
                PublishEntry.config("service.maxRetries", "7")));
        log.info("[conflict] preflight saw v{} but current moved to v{}",
                seenAtPreflight, store.currentVersionNumber());

        PublishBatch stale = PublishBatch.of("bob", "stale update",
                PublishEntry.flag(flag("stale-flag", 100)));
        VersionConflictException conflict = assertThrowsExactly(VersionConflictException.class,
                () -> store.publish(stale, seenAtPreflight));
        log.info("[conflict] stale publish rejected: {}", conflict.getMessage());

        // Distinguishable from an ordinary validation failure, and nothing applied.
        assertNotEquals(InvalidBatchException.class, conflict.getClass());
        assertEquals(2L, store.currentVersionNumber());
        assertFalse(store.current().flags().containsKey("stale-flag"));
        assertEquals("7", store.current().configValues().get("service.maxRetries"));

        // Re-pinning to the real current version succeeds as one whole batch.
        var published = store.publish(stale, store.currentVersionNumber());
        assertEquals(3L, published.versionNumber());
        assertTrue(store.current().flags().containsKey("stale-flag"));

        // Legacy callers without a pin are unaffected.
        var legacy = store.publish(PublishBatch.of("dave", "no pin",
                PublishEntry.flag(flag("legacy-flag", 10))));
        assertEquals(4L, legacy.versionNumber());
        log.info("[conflict] re-pinned publish -> v{}, legacy unpinned -> v{}",
                published.versionNumber(), legacy.versionNumber());
    }

    @Test
    void rollbackPinnedToStaleBaseVersionIsRejectedAsConflict() {
        FeatureFlagStore store = newStore(20);
        store.publish(PublishBatch.of("alice", "v1", PublishEntry.flag(flag("checkout", 100))));
        store.publish(PublishBatch.of("bob", "v2", PublishEntry.flag(flag("other", 50))));

        VersionConflictException conflict = assertThrowsExactly(VersionConflictException.class,
                () -> store.rollbackTo(0, "carol", "stale rollback", 1L));
        log.info("[conflict] stale rollback rejected: {}", conflict.getMessage());
        assertEquals(2L, store.currentVersionNumber());
        assertTrue(store.current().flags().containsKey("checkout"));

        // Pinned to the real current version: rolls back as a new version.
        var rolled = store.rollbackTo(0, "carol", "pinned rollback", 2L);
        assertEquals(3L, rolled.versionNumber());
        assertFalse(store.current().flags().containsKey("checkout"));

        // Legacy rollback without a pin still works.
        var legacy = store.rollbackTo(2, "dave", "unpinned");
        assertEquals(4L, legacy.versionNumber());
        assertTrue(store.current().flags().containsKey("checkout"));
        log.info("[conflict] pinned rollback -> v{}, legacy rollback -> v{}",
                rolled.versionNumber(), legacy.versionNumber());
    }

    @Test
    void diffBetweenRetainedVersionsIsRedactedAndCategorized() {
        FeatureFlagStore store = newStore(20);
        store.publish(PublishBatch.of("alice", "v1",
                PublishEntry.flag(flag("checkout", 100)),
                PublishEntry.config("service.maxRetries", "5"),
                PublishEntry.config("db.password", "secret-one")));
        store.publish(PublishBatch.of("bob", "v2",
                PublishEntry.flag(flag("checkout", 50)),           // overwritten
                PublishEntry.flag(flag("new-flag", 10)),           // added
                PublishEntry.config("db.password", "secret-two")));// overwritten
        store.rollbackTo(1, "carol", "back to v1");                // v3 == v1 content

        VersionDiff diff = store.diff(1, 2);
        log.info("[diff] v1->v2 entries={}", diff.entries());
        Map<String, KeyChange> entries = byKey(diff.entries());
        assertEquals(KeyChangeKind.OVERWRITTEN, entries.get("checkout").kind());
        assertEquals(KeyChangeKind.ADDED, entries.get("new-flag").kind());
        assertEquals(KeyChangeKind.UNCHANGED, entries.get("service.maxRetries").kind());
        assertEquals(KeyChangeKind.OVERWRITTEN, entries.get("db.password").kind());
        assertNotEquals(entries.get("db.password").beforeDigest(),
                entries.get("db.password").afterDigest());

        // Rollback diff shows removals; digests only, raw secrets never appear.
        VersionDiff rolledBack = store.diff(2, 3);
        log.info("[diff] v2->v3 entries={}", rolledBack.entries());
        assertEquals(KeyChangeKind.REMOVED, byKey(rolledBack.entries()).get("new-flag").kind());
        assertFalse(diff.toString().contains("secret-one"));
        assertFalse(diff.toString().contains("secret-two"));
        assertFalse(rolledBack.toString().contains("secret"));
    }

    @Test
    void diffWithEvictedVersionIsDistinguishable() {
        FeatureFlagStore store = newStore(3);
        for (int i = 1; i <= 5; i++) {
            store.publish(PublishBatch.of("alice", "v" + i,
                    PublishEntry.flag(flag("flag-" + i, 100))));
        }
        log.info("[diff] retained history={}",
                store.history().stream().map(v -> "v" + v.versionNumber()).toList());

        VersionNotFoundException evicted = assertThrowsExactly(VersionNotFoundException.class,
                () -> store.diff(0, store.currentVersionNumber()));
        log.info("[diff] evicted version rejected: {}", evicted.getMessage());
        assertThrowsExactly(VersionNotFoundException.class,
                () -> store.diff(store.currentVersionNumber(), 1));

        // Both versions retained -> works.
        VersionDiff ok = store.diff(store.currentVersionNumber() - 1, store.currentVersionNumber());
        assertFalse(ok.entries().isEmpty());
        log.info("[diff] retained diff entries={}", ok.entries());
    }
}
