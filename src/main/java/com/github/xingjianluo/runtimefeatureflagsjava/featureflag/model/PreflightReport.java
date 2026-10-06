package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import java.util.List;

/**
 * Result of a read-only preflight check of a candidate batch against the version
 * that was current when the check ran. Running a preflight changes nothing:
 * no version advance, no state mutation, no effect on evaluations.
 *
 * @param baseVersion the version the batch was checked against (pin commits to this)
 * @param valid       true when no issues were found
 * @param issues      invalid entries, categorized
 * @param changes     per-key preview vs. the base version (redacted digests only)
 */
public record PreflightReport(long baseVersion,
                              boolean valid,
                              List<PreflightIssue> issues,
                              List<KeyChange> changes) {

    public PreflightReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
