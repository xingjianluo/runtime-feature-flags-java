package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import java.util.List;
import java.util.Set;

/**
 * Gray rollout rule for a flag.
 *
 * @param percentage      0..100 inclusive: fraction of subjects selected deterministically
 * @param allowSubjects   subjects always selected (checked after force overrides)
 * @param denySubjects    subjects never selected (checked after allow list)
 * @param forceEnabled    if true, every subject is selected regardless of other rules
 * @param forceDisabled   if true, no subject is selected regardless of other rules
 */
public record RolloutRule(int percentage,
                          Set<String> allowSubjects,
                          Set<String> denySubjects,
                          boolean forceEnabled,
                          boolean forceDisabled) {

    public RolloutRule {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be within [0,100], got " + percentage);
        }
        if (forceEnabled && forceDisabled) {
            throw new IllegalArgumentException("forceEnabled and forceDisabled cannot both be true");
        }
        allowSubjects = allowSubjects == null ? Set.of() : Set.copyOf(allowSubjects);
        denySubjects = denySubjects == null ? Set.of() : Set.copyOf(denySubjects);
    }

    /** Default rule: off for everyone. */
    public static RolloutRule off() {
        return new RolloutRule(0, Set.of(), Set.of(), false, false);
    }

    /** On for everyone. */
    public static RolloutRule allOn() {
        return new RolloutRule(100, Set.of(), Set.of(), false, false);
    }

    public boolean isAllowListed(String subject) {
        return allowSubjects.contains(subject);
    }

    public boolean isDenyListed(String subject) {
        return denySubjects.contains(subject);
    }
}
