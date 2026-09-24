package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Redacts raw values so audit/change logs cannot leak sensitive originals.
 * Uses a non-reversible SHA-256 digest truncated to 12 hex chars; callers can
 * confirm two values were equal without ever seeing the value itself.
 */
public final class ValueRedactor {

    private static final int DIGEST_LENGTH = 12;

    private ValueRedactor() {
    }

    /**
     * Produces a non-reversible marker for a raw value.
     *
     * @param raw raw value (may be null)
     * @return "absent" for null/empty, otherwise "sha256:" + 12 hex chars
     */
    public static String redact(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "absent";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            return "sha256:" + hex.substring(0, DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed on every JVM; if absent, never fall back to the raw value.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
