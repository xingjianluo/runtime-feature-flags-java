package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model;

/**
 * A per-key change between two states (current version vs. candidate batch, or two
 * retained versions). Only redacted digests are exposed; raw values never appear.
 *
 * @param key          flag or config key
 * @param kind         add / overwrite / unchanged / remove
 * @param beforeDigest redacted digest of the old value, or "absent"
 * @param afterDigest  redacted digest of the new value, or "absent"
 */
public record KeyChange(String key, KeyChangeKind kind, String beforeDigest, String afterDigest) {
}
