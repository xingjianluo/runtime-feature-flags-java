package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.DecisionExplanation;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.FeatureFlag;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.RolloutRule;

/**
 * Stateless gray-rollout decision logic.
 *
 * <p>Decision precedence (first match wins), which makes results explainable:
 * <ol>
 *   <li>{@code forceDisabled} -> not selected for everyone</li>
 *   <li>{@code forceEnabled}  -> selected for everyone</li>
 *   <li>deny list membership  -> not selected</li>
 *   <li>allow list membership -> selected</li>
 *   <li>deterministic bucket {@code <} percentage -> selected</li>
 * </ol>
 * With {@code percentage=0} nobody passes; with {@code percentage=100} everyone does
 * (buckets are 0..99, and the rule is {@code bucket < percentage}).
 */
public class RolloutEvaluator {

    private final BucketHasher hasher;

    public RolloutEvaluator(BucketHasher hasher) {
        this.hasher = hasher;
    }

    /**
     * Decide whether a subject is selected by an enabled flag's rollout rule.
     * The caller is responsible for enabled / unknown / rolled-back handling.
     */
    public Decision evaluate(FeatureFlag flag, String subject) {
        if (flag == null) {
            throw new IllegalArgumentException("flag must be non-null");
        }
        if (subject == null) {
            throw new IllegalArgumentException("subject must be non-null");
        }
        RolloutRule rule = flag.rule();
        String key = flag.key();

        if (rule.forceDisabled()) {
            return new Decision(false, DecisionExplanation.of("force-disabled", key, subject));
        }
        if (rule.forceEnabled()) {
            return new Decision(true, DecisionExplanation.of("force-enabled", key, subject));
        }
        if (rule.isDenyListed(subject)) {
            return new Decision(false, DecisionExplanation.of("deny-list", key, subject));
        }
        if (rule.isAllowListed(subject)) {
            return new Decision(true, DecisionExplanation.of("allow-list", key, subject));
        }

        int bucket = hasher.bucket(key, subject);
        int pct = rule.percentage();
        boolean selected = bucket < pct;
        DecisionExplanation explanation =
                new DecisionExplanation("bucket-threshold", key, subject, bucket, pct);
        return new Decision(selected, explanation);
    }

    /** Rule decision plus the explanation of which rule fired. */
    public record Decision(boolean selected, DecisionExplanation explanation) {
    }
}
