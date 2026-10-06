package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.ConfigError;

import java.util.List;

/**
 * Result of a read-only preflight check of a publish batch against the version
 * that was current when the check ran. Running it never mutates state, never
 * advances the version and is invisible to concurrent readers.
 *
 * @param baseVersion version the batch was checked against (use it as expectedVersion
 *                    on the real publish to detect intervening changes)
 * @param valid       true when every entry passed validation
 * @param errors      per-entry validation failures, categorized by {@link ConfigError.Type}
 * @param changes     per-key effect relative to the base version (added/changed/unchanged),
 *                    with redacted before/after summaries only
 */
public record DryRunReport(long baseVersion,
                           boolean valid,
                           List<ConfigError> errors,
                           List<KeyDiff> changes) {

    public DryRunReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
