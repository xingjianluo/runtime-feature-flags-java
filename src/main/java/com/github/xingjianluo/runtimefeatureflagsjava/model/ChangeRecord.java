package com.github.xingjianluo.runtimefeatureflagsjava.model;

import java.time.Instant;

/**
 * 一条配置/开关变更记录。敏感值在记录中只保存脱敏后形态，绝不泄露原值。
 */
public record ChangeRecord(
        String key,
        String kind,
        String oldValueMasked,
        String newValueMasked,
        Instant changedAt,
        String operation
) {
    // 实现在骨架阶段留空
}
