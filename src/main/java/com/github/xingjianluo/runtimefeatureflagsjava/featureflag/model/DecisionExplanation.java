package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * Human/machine-readable explanation of why a flag evaluated as it did.
 * Secrets are never embedded; bucket values are included for boundary debugging.
 *
 * @param reason     short rule that fired, e.g. "force-enabled", "allow-list", "bucket-threshold"
 * @param flagKey    flag that was evaluated
 * @param subject    subject identifier (may itself be sensitive; callers choose whether to log it)
 * @param bucket     0..99 deterministic bucket for the subject, or -1 when bucketing was not used
 * @param percentage threshold in effect, or -1 when percentage was not used
 */
public record DecisionExplanation(String reason,
                                  String flagKey,
                                  String subject,
                                  int bucket,
                                  int percentage) {

    public static DecisionExplanation of(String reason, String flagKey, String subject) {
        return new DecisionExplanation(reason, flagKey, subject, -1, -1);
    }
}
