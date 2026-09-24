package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

import java.util.List;

/**
 * A batch of entries that must all be valid or all rejected;
 * on success they become visible together as one new version.
 */
public record PublishBatch(List<PublishEntry> entries, String operator, String reason) {

    public PublishBatch {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one entry");
        }
        entries = List.copyOf(entries);
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator must be non-blank");
        }
        reason = reason == null ? "" : reason;
    }

    public static PublishBatch of(String operator, String reason, PublishEntry... entries) {
        return new PublishBatch(List.of(entries), operator, reason);
    }
}
