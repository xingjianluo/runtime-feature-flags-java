package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * Machine-distinguishable categories for invalid entries found during preflight,
 * so a rejected batch is never one undifferentiated failure.
 */
public enum PreflightIssueCategory {
    /** Flag rollout rule is internally inconsistent (bad percentage, conflicting force flags). */
    FLAG_RULE_VIOLATION,
    /** Config key is not declared in the schema. */
    UNKNOWN_CONFIG_KEY,
    /** Raw value cannot be converted to the declared type. */
    TYPE_MISMATCH,
    /** Value converts but violates min/max or allowed-values constraints. */
    CONSTRAINT_VIOLATION,
    /** A required config key would be left without any value. */
    MISSING_REQUIRED_VALUE,
    /** No schema-backed config service is available to validate config entries. */
    CONFIG_SCHEMA_UNAVAILABLE
}
