package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * Result of evaluating a flag for a subject. The status is always distinguishable.
 */
public record Evaluation(String flagKey,
                         String subject,
                         boolean enabled,
                         EvaluationStatus status,
                         long observedVersion,
                         DecisionExplanation explanation) {

    public Evaluation {
        if (status == null) {
            throw new IllegalArgumentException("evaluation status must be non-null");
        }
    }
}
