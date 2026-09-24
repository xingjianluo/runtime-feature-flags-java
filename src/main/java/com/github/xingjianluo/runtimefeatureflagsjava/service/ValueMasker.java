package com.github.xingjianluo.runtimefeatureflagsjava.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 敏感配置脱敏：变更记录与查询接口不得泄露原值。
 *
 * <p>敏感值统一输出为 {@code ***（sha256:<前8位>）}：既完全隐藏原值，
 * 又可通过短指纹判断两次取值是否相同，便于审计比对。非敏感值原样返回。
 * 缺失值统一显示为 {@code <none>}，与真实取值区分。
 */
public final class ValueMasker {
    private static final String NONE = "<none>";
    private static final String HIDDEN = "***";

    private ValueMasker() {
    }

    /** 非敏感场景：null 显示为 &lt;none&gt;，其余原样返回。 */
    public static String plain(String value) {
        return value == null ? NONE : value;
    }

    /** 敏感场景：任何非 null 值都替换为星号并附带短指纹；null 显示为 &lt;none&gt;。 */
    public static String mask(String rawValue) {
        if (rawValue == null) {
            return NONE;
        }
        return HIDDEN + "(sha256:" + sha256Prefix(rawValue) + ")";
    }

    /** 按定义的敏感标记选择脱敏方式。 */
    public static String maskIfNeeded(String value, boolean sensitive) {
        return sensitive ? mask(value) : plain(value);
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
