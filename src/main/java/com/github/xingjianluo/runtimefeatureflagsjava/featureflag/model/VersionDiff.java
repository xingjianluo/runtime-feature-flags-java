package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import java.util.List;

/**
 * Redacted difference between two retained versions: key names and digests only,
 * never raw values.
 *
 * @param fromVersion older side of the comparison
 * @param toVersion   newer side of the comparison
 * @param entries     per-key changes (flags and config keys together)
 */
public record VersionDiff(long fromVersion, long toVersion, List<KeyChange> entries) {

    public VersionDiff {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
