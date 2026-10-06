package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * One invalid entry found by preflight. Carries a category so callers can group
 * failures by kind instead of parsing one opaque message. Never echoes raw values.
 *
 * @param key      the offending flag/config key
 * @param category machine-distinguishable failure kind
 * @param detail   human-readable rule description (no raw values)
 */
public record PreflightIssue(String key, PreflightIssueCategory category, String detail) {
}
