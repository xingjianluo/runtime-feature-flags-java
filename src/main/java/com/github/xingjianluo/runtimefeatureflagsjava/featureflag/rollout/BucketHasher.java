package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic, stable hashing of (flag, subject) into a fixed bucket range.
 *
 * <p>Uses 64-bit FNV-1a over {@code flagKey + '#' + subject}. FNV-1a is a pure
 * function (no randomness, no process state), so the same flag/subject pair always
 * maps to the same bucket across processes and restarts. Bucketing is keyed by flag
 * key, so the same subject lands in different buckets for different flags, while
 * remaining stable for a given flag.
 */
public final class BucketHasher {

    public static final int BUCKET_COUNT = 100;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public BucketHasher() {
    }

    /**
     * @param flagKey flag key (salt, keeps buckets independent between flags)
     * @param subject subject identifier
     * @return deterministic bucket in [0,99]
     */
    public int bucket(String flagKey, String subject) {
        if (flagKey == null || subject == null) {
            throw new IllegalArgumentException("flagKey and subject must be non-null");
        }
        byte[] data = (flagKey + '#' + subject).getBytes(StandardCharsets.UTF_8);
        long hash = FNV_OFFSET_BASIS;
        for (byte b : data) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        // unsigned mod keeps the bucket in [0,99]
        return (int) Long.remainderUnsigned(hash, BUCKET_COUNT);
    }
}
