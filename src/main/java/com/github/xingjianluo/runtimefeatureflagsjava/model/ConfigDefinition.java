package com.github.xingjianluo.runtimefeatureflagsjava.model;

import com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.TypeMismatchConfigException;

/**
 * 配置项定义（不可变）：声明键、类型、合法取值范围与默认值。
 * 所有进入发布流程的配置都必须先在注册表中声明，未知键一律拒绝。
 *
 * @param key          配置键，非空白
 * @param type         声明类型
 * @param description  说明
 * @param defaultValue 默认值（可为 null 表示无默认）；存在时必须能通过类型与范围校验
 * @param minValue     数值下界（仅对 INTEGER/LONG 生效），null 表示不限
 * @param maxValue     数值上界（仅对 INTEGER/LONG 生效），null 表示不限
 * @param sensitive    是否敏感；敏感值在变更记录中必须脱敏
 */
public record ConfigDefinition(
        String key,
        ConfigType type,
        String description,
        String defaultValue,
        Long minValue,
        Long maxValue,
        boolean sensitive
) {
    public ConfigDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("配置键不得为空白");
        }
        if (type == null) {
            throw new IllegalArgumentException("配置 " + key + " 的类型不得为 null");
        }
        if (minValue != null && maxValue != null && minValue > maxValue) {
            throw new IllegalArgumentException(
                    "配置 " + key + " 的 minValue(" + minValue + ") 不得大于 maxValue(" + maxValue + ")");
        }
        if (defaultValue != null) {
            // 紧凑构造器内字段尚未赋值，必须用参数局部变量进行默认值校验
            parse(key, type, minValue, maxValue, defaultValue);
        }
    }

    /**
     * 按声明类型解析原始字符串。类型不符抛出 {@link TypeMismatchConfigException}，
     * 范围非法抛出 {@link InvalidValueConfigException}，错误类型可区分，绝不静默回退。
     */
    public Object parse(String raw) {
        return parse(key, type, minValue, maxValue, raw);
    }

    /** 只做校验，不返回结果；供发布批次在原子生效前逐条验证。 */
    public void validateRaw(String raw) {
        parse(raw);
    }

    private static Object parse(String key, ConfigType type, Long minValue, Long maxValue, String raw) {
        return switch (type) {
            case STRING -> raw;
            case INTEGER -> {
                int value = parseInt(key, raw);
                checkRange(key, minValue, maxValue, value);
                yield value;
            }
            case LONG -> {
                long value = parseLong(key, raw);
                checkRange(key, minValue, maxValue, value);
                yield value;
            }
            case BOOLEAN -> parseBoolean(key, raw);
            case DOUBLE -> parseDouble(key, raw);
        };
    }

    private static int parseInt(String key, String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new TypeMismatchConfigException(
                    "配置 " + key + " 期望 INTEGER，但取值无法解析: " + raw, e);
        }
    }

    private static long parseLong(String key, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new TypeMismatchConfigException(
                    "配置 " + key + " 期望 LONG，但取值无法解析: " + raw, e);
        }
    }

    private static double parseDouble(String key, String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new TypeMismatchConfigException(
                    "配置 " + key + " 期望 DOUBLE，但取值无法解析: " + raw, e);
        }
    }

    private static boolean parseBoolean(String key, String raw) {
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        throw new TypeMismatchConfigException(
                "配置 " + key + " 期望 BOOLEAN(true/false)，但取值无法解析: " + raw);
    }

    private static void checkRange(String key, Long minValue, Long maxValue, long value) {
        if (minValue != null && value < minValue) {
            throw new InvalidValueConfigException(
                    "配置 " + key + " 的值 " + value + " 小于下界 " + minValue);
        }
        if (maxValue != null && value > maxValue) {
            throw new InvalidValueConfigException(
                    "配置 " + key + " 的值 " + value + " 大于上界 " + maxValue);
        }
    }
}
