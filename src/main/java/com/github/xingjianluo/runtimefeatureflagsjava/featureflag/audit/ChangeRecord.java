package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.audit;

/**
 * Redacted audit entry describing one change. Raw values (especially secret ones)
 * are never stored here; only the key, the kind of change and a redacted value digest.
 *
 * @param versionNumber version in which the change appeared
 * @param key           flag/config key
 * @param kind          UPSERT_FLAG, UPSERT_CONFIG, ROLLBACK
 * @param redactedValue non-reversible digest or "***" marker; never the raw value
 * @param operator      who made the change
 * @param reason        operator-supplied reason
 * @param at            epoch millis
 */
public record ChangeRecord(long versionNumber,
                           String key,
                           String kind,
                           String redactedValue,
                           String operator,
                           String reason,
                           long at) {
}
