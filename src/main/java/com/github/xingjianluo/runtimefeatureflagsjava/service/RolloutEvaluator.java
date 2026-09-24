package com.github.xingjianluo.runtimefeatureflagsjava.service;

import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigVersion;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagEvaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagStatus;

/**
 * 灰度判定器。
 *
 * <p>对一个不可变版本快照做判定，优先级（从高到低）：
 * <ol>
 *   <li>forceOn / forceOff 强制覆盖（forceOn 与 forceOff 互斥，发布期已校验）</li>
 *   <li>denyList 黑名单 → OFF</li>
 *   <li>allowList 白名单 → ON</li>
 *   <li>percent 百分比：bucket = SHA-256(flagKey, subjectId) mod 100，bucket &lt; percent → ON</li>
 * </ol>
 * 同一 (flagKey, subjectId) 的桶位恒定，因此同一版本下结果稳定，不受线程与时间影响。
 * 判定结果携带 reason 文本，完整记录命中依据，便于审计与日志解释。
 */
public class RolloutEvaluator {

    /** 仅在给定版本内判定；版本中不存在该开关时返回 UNKNOWN（是否回滚由门面层区分）。 */
    public FlagEvaluation evaluate(ConfigVersion version, String flagKey, String subjectId) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey 不得为空白");
        }
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId 不得为空白");
        }
        FlagRule rule = version.flags().get(flagKey);
        if (rule == null) {
            return new FlagEvaluation(FlagStatus.UNKNOWN,
                    "开关 " + flagKey + " 在版本 " + version.version() + " 中不存在",
                    -1, version.version());
        }

        int bucket = BucketHasher.bucket(flagKey, subjectId);
        String base = "flag=" + flagKey + ", subject=" + subjectId + ", bucket=" + bucket;

        if (rule.forceOn().contains(subjectId)) {
            return new FlagEvaluation(FlagStatus.ON,
                    base + ", 命中 forceOn 强制开启", bucket, version.version());
        }
        if (rule.forceOff().contains(subjectId)) {
            return new FlagEvaluation(FlagStatus.OFF,
                    base + ", 命中 forceOff 强制关闭", bucket, version.version());
        }
        if (rule.denyList().contains(subjectId)) {
            return new FlagEvaluation(FlagStatus.OFF,
                    base + ", 命中 denyList 黑名单", bucket, version.version());
        }
        if (rule.allowList().contains(subjectId)) {
            return new FlagEvaluation(FlagStatus.ON,
                    base + ", 命中 allowList 白名单", bucket, version.version());
        }
        if (bucket < rule.percent()) {
            return new FlagEvaluation(FlagStatus.ON,
                    base + ", percent=" + rule.percent() + ", bucket<percent 命中",
                    bucket, version.version());
        }
        return new FlagEvaluation(FlagStatus.OFF,
                base + ", percent=" + rule.percent() + ", bucket>=percent 未命中",
                bucket, version.version());
    }
}
