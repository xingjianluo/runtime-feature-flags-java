package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * One key-level difference between two states. Summaries are always redacted:
 * config values appear only as non-reversible digests, flags as structural
 * fingerprints; raw (possibly secret) values are never exposed.
 *
 * @param key           flag or config key
 * @param kind          what would happen / what happened to this key
 * @param beforeSummary redacted summary in the base state ("absent" when not present)
 * @param afterSummary  redacted summary in the target state ("absent" when not present)
 */
public record KeyDiff(String key, Kind kind, String beforeSummary, String afterSummary) {

    public enum Kind {
        /** Present only in the target state. */
        ADDED,
        /** Present only in the base state. */
        REMOVED,
        /** Present in both but with a different value/structure. */
        CHANGED,
        /** Present in both, identical. */
        UNCHANGED
    }

    public KeyDiff {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("KeyDiff.key must be non-blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("KeyDiff.kind must be non-null");
        }
        beforeSummary = beforeSummary == null ? "absent" : beforeSummary;
        afterSummary = afterSummary == null ? "absent" : afterSummary;
    }
}
