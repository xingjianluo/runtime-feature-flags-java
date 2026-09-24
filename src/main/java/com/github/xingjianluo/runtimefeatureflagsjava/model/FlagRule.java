package com.github.xingjianluo.runtimefeatureflagsjava.model;

import java.util.Set;

import com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException;

import java.util.HashSet;
import java.util.Set;

/**
 * 特性开关灰度规则（不可变）。
 *
 * <p>判定优先级（从高到低）：
 * <ol>
 *   <li>{@code forceOn} / {@code forceOff}：强制覆盖；同一主体不得同时出现在两者中</li>
 *   <li>{@code denyList}：黑名单，强制不命中</li>
 *   <li>{@code allowList}：白名单，强制命中</li>
 *   <li>{@code percent}：按确定性桶位判定，桶位 &lt; percent 命中</li>
 * </ol>
 *
 * @param percent   0-100 的灰度百分比（基于主体标识确定性分桶）
 * @param allowList 白名单主体，强制命中
 * @param denyList  黑名单主体，强制不命中
 * @param forceOn   强制开启的主体（优先级最高）
 * @param forceOff  强制关闭的主体（优先级最高，与 forceOn 对同一主体同时存在时报错）
 */
public record FlagRule(
        int percent,
        Set<String> allowList,
        Set<String> denyList,
        Set<String> forceOn,
        Set<String> forceOff
) {
    public FlagRule {
        if (percent < 0 || percent > 100) {
            throw new InvalidValueConfigException("灰度百分比必须在 [0,100] 内，实际为 " + percent);
        }
        allowList = immutableCopy(allowList);
        denyList = immutableCopy(denyList);
        forceOn = immutableCopy(forceOn);
        forceOff = immutableCopy(forceOff);
        validateNoBlank(allowList, "allowList");
        validateNoBlank(denyList, "denyList");
        validateNoBlank(forceOn, "forceOn");
        validateNoBlank(forceOff, "forceOff");
    }

    /**
     * 跨集合语义校验：同一主体不得同时出现在互斥集合中，否则规则存在歧义，必须拒绝整批发布。
     */
    public void validate(String flagKey) {
        Set<String> onOff = new HashSet<>(forceOn);
        onOff.retainAll(forceOff);
        if (!onOff.isEmpty()) {
            throw new InvalidValueConfigException(
                    "开关 " + flagKey + " 的主体同时出现在 forceOn 与 forceOff 中: " + onOff);
        }
        Set<String> allowDeny = new HashSet<>(allowList);
        allowDeny.retainAll(denyList);
        if (!allowDeny.isEmpty()) {
            throw new InvalidValueConfigException(
                    "开关 " + flagKey + " 的主体同时出现在 allowList 与 denyList 中: " + allowDeny);
        }
    }

    private static Set<String> immutableCopy(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(source);
    }

    private static void validateNoBlank(Set<String> values, String field) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new InvalidValueConfigException(field + " 中不得包含 null 或空白主体标识");
            }
        }
    }
}
