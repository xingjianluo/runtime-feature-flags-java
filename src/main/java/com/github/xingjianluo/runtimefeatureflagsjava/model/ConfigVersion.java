package com.github.xingjianluo.runtimefeatureflagsjava.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 一个不可变的整批配置版本快照：配置值与开关规则随版本原子生效。
 *
 * <p>构造时对所有集合做防御性拷贝并冻结，发布后任何外部修改都无法影响已发布版本；
 * 读取方拿到的永远是某一版本的完整一致视图，不会看到「配置已更新、开关尚未更新」
 * 之类的中间态。
 */
public record ConfigVersion(
        long version,
        Instant publishedAt,
        String publishedBy,
        String note,
        Map<String, String> configValues,
        Map<String, FlagRule> flags,
        List<ChangeRecord> changes
) {
    public ConfigVersion {
        if (version < 0) {
            throw new IllegalArgumentException("版本号不得为负: " + version);
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException("publishedAt 不得为 null");
        }
        configValues = Map.copyOf(configValues == null ? Map.of() : configValues);
        flags = Map.copyOf(flags == null ? Map.of() : flags);
        changes = List.copyOf(changes == null ? List.of() : changes);
    }

    /** 版本号 0 的空初始版本：系统启动时尚无任何发布内容。 */
    public static ConfigVersion empty() {
        return new ConfigVersion(0L, Instant.EPOCH, "system", "初始空版本",
                Map.of(), Map.of(), List.of());
    }
}
