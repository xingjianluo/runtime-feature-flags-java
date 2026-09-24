package com.github.xingjianluo.runtimefeatureflagsjava.service;

import com.github.xingjianluo.runtimefeatureflagsjava.error.BatchValidationException;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ChangeRecord;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigVersion;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagEvaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 对外门面：整批原子发布 / 回滚、基于快照的一致性灰度判定、分层配置读取与变更查询。
 *
 * <p>发布与回滚都先生成不可变版本快照，再让配置层与开关层在同一瞬间切换：
 * 读线程要么看到旧版本的全部内容，要么看到新版本的全部内容。
 */
public class FeatureFlagService {
    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final VersionStore store;
    private final LayeredConfigService configService;
    private final RolloutEvaluator evaluator = new RolloutEvaluator();

    public FeatureFlagService(VersionStore store, LayeredConfigService configService) {
        this.store = store;
        this.configService = configService;
    }

    /** 整批发布；任一条目校验失败时抛出 {@link BatchValidationException}，线上版本不变。 */
    public long publishBatch(Map<String, String> configs, Map<String, FlagRule> flags,
                             String publishedBy, String note) {
        int configCount = configs == null ? 0 : configs.size();
        int flagCount = flags == null ? 0 : flags.size();
        long fromVersion = store.current().version();
        log.info("发布请求: by={}, note='{}', 基线版本={}, 配置条目数={}, 开关条目数={}, flags={}",
                publishedBy, note, fromVersion, configCount, flagCount, flagKeys(flags));
        try {
            // 配置层与开关层都直接读取 store 的当前快照，提交后两者原子可见，无需二次切换
            ConfigVersion version = store.publish(configs, flags, publishedBy, note);
            log.info("发布成功: 新版本={}, 变更条目数={}", version.version(), version.changes().size());
            return version.version();
        } catch (BatchValidationException e) {
            log.warn("发布被整批拒绝: by={}, 基线版本={}, 错误数={}, 错误={}",
                    publishedBy, fromVersion, e.getErrors().size(),
                    e.getErrors().stream().map(Throwable::getMessage).toList());
            throw e;
        }
    }

    /** 回滚到指定历史版本（会产生新版本号），配置层同步原子切换。 */
    public long rollback(long targetVersion, String publishedBy) {
        long fromVersion = store.current().version();
        log.info("回滚请求: by={}, 当前版本={}, 目标版本={}", publishedBy, fromVersion, targetVersion);
        ConfigVersion version = store.rollbackTo(targetVersion, publishedBy);
        log.info("回滚成功: 新版本={}, 内容恢复自版本={}", version.version(), targetVersion);
        return version.version();
    }

    /**
     * 灰度判定。返回四态可区分结果：
     * ON / OFF（当前版本存在该开关）、UNKNOWN（从未发布过）、ROLLED_BACK（曾存在但当前版本没有）。
     */
    public FlagEvaluation evaluate(String flagKey, String subjectId) {
        return evaluateOn(store.current(), flagKey, subjectId);
    }

    /** 基于指定版本快照判定；与 {@link #resolveOn} 配合可在同一观测内严格不跨版本。 */
    public FlagEvaluation evaluateOn(ConfigVersion version, String flagKey, String subjectId) {
        boolean existsInCurrent = version.flags().containsKey(flagKey);
        FlagEvaluation evaluation = evaluator.evaluate(version, flagKey, subjectId);
        if (!existsInCurrent && store.hasEverExisted(VersionStore.FLAG_KEY_PREFIX + flagKey)) {
            evaluation = new FlagEvaluation(FlagStatus.ROLLED_BACK,
                    "flag=" + flagKey + ", subject=" + subjectId
                            + ", 开关曾发布但在当前版本 " + version.version() + " 中不存在（已回滚/移除）",
                    -1, version.version());
        }
        // 打印输入与判定依据，便于测试与审计；名单主体不打印任何具体原值
        log.info("灰度判定: flag={}, subject={}, version={}, status={}, reason='{}'",
                flagKey, subjectId, version.version(), evaluation.status(), evaluation.reason());
        return evaluation;
    }

    /** 取当前不可变版本快照；读线程在一次观测内持有它即可保证视图一致。 */
    public ConfigVersion snapshot() {
        return store.current();
    }

    /** 基于指定版本快照解析配置，保证与同快照的开关判定处于同一版本。 */
    public LayeredConfigService.ResolvedConfig resolveOn(ConfigVersion version, String key) {
        LayeredConfigService.ResolvedConfig resolved =
                configService.resolve(key, version.configValues());
        log.info("配置读取(快照): key={}, version={}, 命中层={}({})",
                key, version.version(), resolved.layer().displayName(), resolved.layer().precedence());
        return resolved;
    }

    /** 读取分层合并后的配置（缺失/类型/越界均以可区分异常抛出）。 */
    public String getConfig(String key) {
        return resolvedConfig(key).rawValue();
    }

    /** 读取配置值及其命中层，用于接口解释生效来源。 */
    public LayeredConfigService.ResolvedConfig resolvedConfig(String key) {
        LayeredConfigService.ResolvedConfig resolved = configService.resolve(key);
        log.info("配置读取: key={}, 命中层={}({})", key, resolved.layer().displayName(),
                resolved.layer().precedence());
        return resolved;
    }

    /** 查询变更记录（敏感值已脱敏，名单不出现具体主体）。 */
    public List<ChangeRecord> changeHistory(String key) {
        List<ChangeRecord> records = store.changeHistory(key);
        log.info("变更查询: key={}, 记录数={}", key, records.size());
        return records;
    }

    public long currentVersion() {
        return store.current().version();
    }

    private static String flagKeys(Map<String, FlagRule> flags) {
        return flags == null ? "[]" : flags.keySet().toString();
    }
}
