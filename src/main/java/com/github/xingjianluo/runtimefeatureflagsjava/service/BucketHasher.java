package com.github.xingjianluo.runtimefeatureflagsjava.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 基于「开关键 + 主体标识」的确定性分桶哈希。
 *
 * <p>使用 SHA-256 对 flagKey + 分隔符 + subjectId 求值，取前 8 字节为无符号 long，
 * 再对 100 取模得到 0-99 的桶位。只要 (flagKey, subjectId) 不变，桶位就稳定不变，
 * 且与读取时机、线程、发布顺序无关。
 */
public final class BucketHasher {
    /** 键与主体之间的分隔符，避免拼接歧义。 */
    private static final byte SEPARATOR = 0x1f;

    private BucketHasher() {
    }

    /** 返回主体在指定开关上的确定性桶位，范围 [0, 99]。 */
    public static int bucket(String flagKey, String subjectId) {
        if (flagKey == null || subjectId == null) {
            throw new IllegalArgumentException("flagKey 与 subjectId 均不得为 null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(flagKey.getBytes(StandardCharsets.UTF_8));
            digest.update(SEPARATOR);
            byte[] hash = digest.digest(subjectId.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xffL);
            }
            return (int) Long.remainderUnsigned(value, 100L);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不会缺失
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
