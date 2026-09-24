package com.github.xingjianluo.runtimefeatureflagsjava.model;

/** 灰度判定的可区分结果状态。 */
public enum FlagStatus {
    /** 开关存在且命中（强制开启、名单命中或落入百分比桶）。 */
    ON,
    /** 开关存在但未命中。 */
    OFF,
    /** 开关键不存在（从未发布或定义）。 */
    UNKNOWN,
    /** 开关曾存在，但当前生效版本中已被移除/随回滚撤销。 */
    ROLLED_BACK
}
