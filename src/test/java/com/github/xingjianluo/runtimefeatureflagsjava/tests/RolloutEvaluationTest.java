package com.github.xingjianluo.runtimefeatureflagsjava.tests;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Evaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.EvaluationStatus;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.FeatureFlag;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishBatch;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishEntry;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.RolloutRule;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.BucketHasher;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.RolloutEvaluator;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.store.FeatureFlagStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic gray rollout, bucket boundaries, lists, force overrides and
 * distinguishable evaluation statuses. Every case logs inputs and the decision basis.
 */
class RolloutEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(RolloutEvaluationTest.class);

    private final BucketHasher hasher = new BucketHasher();
    private final RolloutEvaluator evaluator = new RolloutEvaluator(hasher);

    private FeatureFlagStore newStore() {
        LongSupplier clock = new AtomicLong(1000)::incrementAndGet;
        return new FeatureFlagStore(20, clock, evaluator, null);
    }

    @Test
    void sameFlagAndSubjectAlwaysHashToTheSameBucket() {
        Map<String, Integer> first = new HashMap<>();
        for (String subject : new String[]{"alice", "bob", "carol", "user-42", "中文用户"}) {
            first.put(subject, hasher.bucket("checkout-redesign", subject));
        }
        for (int i = 0; i < 1000; i++) {
            first.forEach((subject, bucket) ->
                    assertEquals(bucket, hasher.bucket("checkout-redesign", subject),
                            "bucket drifted for " + subject));
        }
        // Flag key salts the hash: a subject can land in a different bucket per flag.
        log.info("[determinism] stable buckets over 1000 repeats={}; alice buckets flagA={} flagB={}",
                first, hasher.bucket("flagA", "alice"), hasher.bucket("flagB", "alice"));
    }

    @Test
    void bucketBoundaryAtPercentageIsExclusive() {
        // Find real subjects whose bucket is exactly 0 and 1 for this flag (no mocking of hashing).
        String bucketZero = null;
        String bucketOne = null;
        for (int i = 0; i < 200_000 && (bucketZero == null || bucketOne == null); i++) {
            String subject = "boundary-subject-" + i;
            int bucket = hasher.bucket("boundary-flag", subject);
            if (bucket == 0) {
                bucketZero = subject;
            }
            if (bucket == 1) {
                bucketOne = subject;
            }
        }
        assertNotNull(bucketZero);
        assertNotNull(bucketOne, "FNV-1a should find a bucket-1 subject quickly");
        log.info("[boundary] subjects bucket0='{}'(b=0), bucket1='{}'(b=1); rule bucket < percentage",
                bucketZero, bucketOne);

        FeatureFlag onePercent = FeatureFlag.enabled("boundary-flag",
                new RolloutRule(1, Set.of(), Set.of(), false, false));

        RolloutEvaluator.Decision d0 = evaluator.evaluate(onePercent, bucketZero);
        RolloutEvaluator.Decision d1 = evaluator.evaluate(onePercent, bucketOne);
        log.info("[boundary] pct=1 -> bucket0 selected={} reason={} | bucket1 selected={} reason={}",
                d0.selected(), d0.explanation().reason(), d1.selected(), d1.explanation().reason());
        assertTrue(d0.selected(), "bucket 0 must be selected at percentage=1 (0 < 1)");
        assertFalse(d1.selected(), "bucket 1 must NOT be selected at percentage=1 (1 < 1 is false)");

        // 0% selects nobody, 100% selects everybody (boundaries of the whole range).
        FeatureFlag zero = FeatureFlag.enabled("boundary-flag", RolloutRule.off());
        FeatureFlag full = FeatureFlag.enabled("boundary-flag", RolloutRule.allOn());
        assertFalse(evaluator.evaluate(zero, bucketZero).selected());
        assertTrue(evaluator.evaluate(full, bucketZero).selected());
        assertTrue(evaluator.evaluate(full, bucketOne).selected());
        log.info("[boundary] pct=0 -> bucket0 selected={}; pct=100 -> both selected={}",
                evaluator.evaluate(zero, bucketZero).selected(),
                evaluator.evaluate(full, bucketOne).selected());
    }

    @Test
    void forceOverridesAndListsFollowPrecedence() {
        String vip = "vip-1";
        String blocked = "blocked-1";
        RolloutRule rule = new RolloutRule(0, Set.of(vip), Set.of(blocked), true, false);
        FeatureFlag forceOn = FeatureFlag.enabled("promo", rule);

        // forceEnabled wins over lists and 0% bucket.
        assertTrue(evaluator.evaluate(forceOn, blocked).selected());
        log.info("[precedence] forceEnabled subject='{}' -> selected={} reason={}",
                blocked, evaluator.evaluate(forceOn, blocked).selected(),
                evaluator.evaluate(forceOn, blocked).explanation().reason());

        // forceDisabled beats allow-list.
        FeatureFlag forceOff = FeatureFlag.enabled("promo",
                new RolloutRule(100, Set.of(vip), Set.of(), false, true));
        var decision = evaluator.evaluate(forceOff, vip);
        assertFalse(decision.selected());
        assertEquals("force-disabled", decision.explanation().reason());
        log.info("[precedence] forceDisabled subject='{}' (in allow list) -> selected={} reason={}",
                vip, decision.selected(), decision.explanation().reason());

        // Deny beats allow-independent percentage; allow beats percentage.
        FeatureFlag lists = FeatureFlag.enabled("promo",
                new RolloutRule(0, Set.of(vip), Set.of(blocked), false, false));
        assertTrue(evaluator.evaluate(lists, vip).selected());
        assertFalse(evaluator.evaluate(lists, blocked).selected());
    }

    @Test
    void evaluationStatusesAreDistinguishableAndStable() {
        FeatureFlagStore store = newStore();
        FeatureFlag enabled = FeatureFlag.enabled("new-checkout",
                new RolloutRule(100, Set.of(), Set.of(), false, false));
        FeatureFlag disabled = FeatureFlag.disabled("dark-mode");
        store.publish(PublishBatch.of("alice", "seed",
                PublishEntry.flag(enabled), PublishEntry.flag(disabled)));

        Evaluation on = store.evaluate("new-checkout", "user-a");
        Evaluation off = store.evaluate("dark-mode", "user-a");
        Evaluation unknown = store.evaluate("ghost-flag", "user-a");

        log.info("[status] input(key='new-checkout',subject='user-a') -> status={} enabled={} reason={} v={}",
                on.status(), on.enabled(), on.explanation().reason(), on.observedVersion());
        log.info("[status] input(key='dark-mode',subject='user-a') -> status={} reason={}",
                off.status(), off.explanation().reason());
        log.info("[status] input(key='ghost-flag',subject='user-a') -> status={} reason={}",
                unknown.status(), unknown.explanation().reason());

        assertEquals(EvaluationStatus.ENABLED, on.status());
        assertTrue(on.enabled());
        assertEquals(EvaluationStatus.DISABLED, off.status());
        assertFalse(off.enabled());
        assertEquals(EvaluationStatus.UNKNOWN_FLAG, unknown.status());

        // Stability: repeated reads never drift for the same version + subject.
        for (int i = 0; i < 100; i++) {
            assertEquals(on.enabled(), store.evaluate("new-checkout", "user-a").enabled());
            assertEquals(unknown.status(), store.evaluate("ghost-flag", "user-a").status());
        }
    }

    @Test
    void rolledBackFlagStatusDiffersFromUnknownAndDisabled() {
        FeatureFlagStore store = newStore();
        store.publish(PublishBatch.of("alice", "v1",
                PublishEntry.flag(FeatureFlag.enabled("temp-experiment", RolloutRule.allOn()))));
        store.rollbackTo(0, "bob", "end experiment");

        Evaluation rolledBack = store.evaluate("temp-experiment", "user-z");
        Evaluation unknown = store.evaluate("other-temp", "user-z");
        log.info("[rollbackStatus] rolled-back flag -> status={}; never-seen flag -> status={}",
                rolledBack.status(), unknown.status());
        assertEquals(EvaluationStatus.ROLLED_BACK, rolledBack.status());
        assertEquals(EvaluationStatus.UNKNOWN_FLAG, unknown.status());
    }
}
