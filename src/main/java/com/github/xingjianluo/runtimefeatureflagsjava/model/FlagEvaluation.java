package com.github.xingjianluo.runtimefeatureflagsjava.model;

/**
 * 一次灰度判定的可解释结果。
 *
 * @param status   ON / OFF / UNKNOWN / ROLLED_BACK
 * @param reason   判定依据，例如 "force-on override" / "bucket 37 < percent 50"
 * @param bucket   主体在该开关上的确定性桶位（0-99），未知/回滚时为 -1
 * @param version  判定所基于的配置版本号，未知/回滚时为 -1
 */
public record FlagEvaluation(
        FlagStatus status,
        String reason,
        int bucket,
        long version
) {
    // 实现在骨架阶段留空
}
