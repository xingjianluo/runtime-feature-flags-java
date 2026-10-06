package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import java.util.List;

/**
 * Key-level difference between two retained versions. Only key names and redacted
 * summaries are exposed; raw values never leave the store.
 *
 * @param fromVersion base version number
 * @param toVersion   target version number
 * @param changes     keys that differ (added / removed / changed); unchanged keys omitted
 */
public record VersionDiff(long fromVersion, long toVersion, List<KeyDiff> changes) {

    public VersionDiff {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
