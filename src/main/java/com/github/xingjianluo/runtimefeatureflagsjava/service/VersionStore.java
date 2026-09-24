package com.github.xingjianluo.runtimefeatureflagsjava.service;

import com.github.xingjianluo.runtimefeatureflagsjava.model.ChangeRecord;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigVersion;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;

import com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ChangeRecord;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigDefinition;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigRegistry;
import com.github.xingjianluo.runtimefeatureflagsjava.model.ConfigVersion;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagRule;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 版本存储：整批原子发布、有限历史、回滚与变更审计。
 *
 * <p>一致性保证：
 * <ul>
 *   <li>写操作（publish / rollback）在同一把锁内串行完成「校验 → 构建快照 → 替换引用」，
 *       任一条目校验失败则整体抛出，已发布版本保持不变，不存在部分更新。</li>
 *   <li>读操作只读 {@code volatile} 的不可变 {@link ConfigVersion} 快照，
 *       并发读写时任何读取看到的都是某一版本的完整内容。</li>
 *   <li>历史按版本号倒序保留有限条数；旧版本被裁剪后，其已存在的键仍记录在
 *       审计索引中，用于区分「从未存在(UNKNOWN)」与「存在过但当前不在(ROLLED_BACK)」。</li>
 * </ul>
 */
public class VersionStore {
    /** 开关在审计记录中的键前缀，避免与配置键混淆。 */
    public static final String FLAG_KEY_PREFIX = "flag:";

    private final ConfigRegistry registry;
    private final int historyLimit;

    private final Object lock = new Object();
    private final Deque<ConfigVersion> history = new ArrayDeque<>();
    private final Map<String, List<ChangeRecord>> audit = new LinkedHashMap<>();
    /** 曾经存在过的审计键（配置键原名，开关键带 flag: 前缀）。 */
    private final java.util.Set<String> everExisted = new TreeSet<>();
    private long nextVersion = 1L;
    private volatile ConfigVersion current = ConfigVersion.empty();

    public VersionStore(ConfigRegistry registry, int historyLimit) {
        if (historyLimit < 2) {
            throw new IllegalArgumentException("历史保留条数至少为 2（含当前版本），实际为 " + historyLimit);
        }
        this.registry = registry;
        this.historyLimit = historyLimit;
        history.add(current);
    }

    /**
     * 整批原子发布。configs 与 flags 在同一新版本中同时生效。
     * 校验失败时抛出 {@link com.github.xingjianluo.runtimefeatureflagsjava.error.BatchValidationException}，
     * 当前版本不变。
     */
    public ConfigVersion publish(Map<String, String> configValues, Map<String, FlagRule> flags,
                                 String publishedBy, String note) {
        Map<String, String> safeConfigs = configValues == null ? Map.of() : new LinkedHashMap<>(configValues);
        Map<String, FlagRule> safeFlags = flags == null ? Map.of() : new LinkedHashMap<>(flags);
        if (publishedBy == null || publishedBy.isBlank()) {
            throw new InvalidValueConfigException("publishedBy 不得为空白");
        }
        List<ChangeRecord> changes;
        synchronized (lock) {
            validateBatch(safeConfigs, safeFlags); // 先完整校验，任一失败则不触碰任何状态
            ConfigVersion previous = current;
            changes = diff(previous, safeConfigs, safeFlags, "PUBLISH");
            ConfigVersion version = new ConfigVersion(
                    nextVersion++, Instant.now(), publishedBy, note,
                    safeConfigs, safeFlags, changes);
            commit(version, changes);
            return version;
        }
    }

    /**
     * 回滚到历史版本：以该版本的完整内容生成一个新版本（审计上可追溯），
     * 而不是删除历史。目标版本必须仍在保留窗口内。
     */
    public ConfigVersion rollbackTo(long targetVersion, String publishedBy) {
        if (publishedBy == null || publishedBy.isBlank()) {
            throw new InvalidValueConfigException("publishedBy 不得为空白");
        }
        synchronized (lock) {
            ConfigVersion target = history.stream()
                    .filter(v -> v.version() == targetVersion)
                    .findFirst()
                    .orElseThrow(() -> new InvalidValueConfigException(
                            "回滚目标版本不存在或已超出历史保留窗口: " + targetVersion));
            Map<String, String> configs = new LinkedHashMap<>(target.configValues());
            Map<String, FlagRule> flags = new LinkedHashMap<>(target.flags());
            List<ChangeRecord> changes = diff(current, configs, flags, "ROLLBACK->" + targetVersion);
            ConfigVersion version = new ConfigVersion(
                    nextVersion++, Instant.now(), publishedBy,
                    "回滚到版本 " + targetVersion, configs, flags, changes);
            commit(version, changes);
            return version;
        }
    }

    public ConfigVersion current() {
        return current;
    }

    /** 返回历史快照，版本号倒序（最新在前），不可变。 */
    public List<ConfigVersion> history() {
        synchronized (lock) {
            return history.stream().toList();
        }
    }

    /** 某键的全部变更记录（配置键传原名；开关键传原名，内部同时匹配 flag: 前缀），按时间正序。 */
    public List<ChangeRecord> changeHistory(String key) {
        synchronized (lock) {
            List<ChangeRecord> result = new ArrayList<>();
            List<ChangeRecord> direct = audit.get(key);
            if (direct != null) {
                result.addAll(direct);
            }
            if (!key.startsWith(FLAG_KEY_PREFIX)) {
                List<ChangeRecord> flagRecords = audit.get(FLAG_KEY_PREFIX + key);
                if (flagRecords != null) {
                    result.addAll(flagRecords);
                }
            }
            return List.copyOf(result);
        }
    }

    /** 审计键是否在任意已发布版本中存在过（区分 UNKNOWN 与 ROLLED_BACK）。 */
    public boolean hasEverExisted(String auditKey) {
        synchronized (lock) {
            return everExisted.contains(auditKey);
        }
    }

    // ---- 内部实现（均在锁内调用）----

    private void commit(ConfigVersion version, List<ChangeRecord> changes) {
        history.addFirst(version);
        while (history.size() > historyLimit) {
            history.removeLast();
        }
        for (ChangeRecord record : changes) {
            audit.computeIfAbsent(record.key(), k -> new ArrayList<>()).add(record);
            everExisted.add(record.key());
        }
        current = version; // 最后一步发布引用：此前的所有修改对读线程均不可见
    }

    private void validateBatch(Map<String, String> configs, Map<String, FlagRule> flags) {
        List<com.github.xingjianluo.runtimefeatureflagsjava.error.ConfigException> errors = new ArrayList<>();
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            try {
                if (key == null || key.isBlank()) {
                    throw new InvalidValueConfigException("配置键不得为空白");
                }
                ConfigDefinition definition = registry.find(key).orElseThrow(() ->
                        new com.github.xingjianluo.runtimefeatureflagsjava.error.UnknownConfigKeyException(
                                "批次中存在未注册的配置键: " + key));
                if (entry.getValue() == null) {
                    throw new InvalidValueConfigException("配置 " + key + " 的值不得为 null（删除请使用回滚）");
                }
                definition.validateRaw(entry.getValue());
            } catch (com.github.xingjianluo.runtimefeatureflagsjava.error.ConfigException e) {
                errors.add(e);
            }
        }
        for (Map.Entry<String, FlagRule> entry : flags.entrySet()) {
            String key = entry.getKey();
            try {
                if (key == null || key.isBlank()) {
                    throw new InvalidValueConfigException("开关键不得为空白");
                }
                if (entry.getValue() == null) {
                    throw new InvalidValueConfigException("开关 " + key + " 的规则不得为 null");
                }
                entry.getValue().validate(key);
            } catch (com.github.xingjianluo.runtimefeatureflagsjava.error.ConfigException e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            throw new com.github.xingjianluo.runtimefeatureflagsjava.error.BatchValidationException(errors);
        }
    }

    private List<ChangeRecord> diff(ConfigVersion previous,
                                    Map<String, String> newConfigs,
                                    Map<String, FlagRule> newFlags,
                                    String operation) {
        Instant now = Instant.now();
        List<ChangeRecord> records = new ArrayList<>();
        Map<String, String> oldConfigs = previous.configValues();
        for (Map.Entry<String, String> entry : newConfigs.entrySet()) {
            String key = entry.getKey();
            String oldValue = oldConfigs.get(key);
            String newValue = entry.getValue();
            boolean sensitive = registry.find(key).map(ConfigDefinition::sensitive).orElse(false);
            records.add(new ChangeRecord(key, "CONFIG",
                    ValueMasker.maskIfNeeded(oldValue, sensitive),
                    ValueMasker.maskIfNeeded(newValue, sensitive), now, operation));
        }
        for (String removed : oldConfigs.keySet()) {
            if (!newConfigs.containsKey(removed)) {
                boolean sensitive = registry.find(removed).map(ConfigDefinition::sensitive).orElse(false);
                records.add(new ChangeRecord(removed, "CONFIG",
                        ValueMasker.maskIfNeeded(oldConfigs.get(removed), sensitive),
                        ValueMasker.plain(null), now, operation));
            }
        }
        Map<String, FlagRule> oldFlags = previous.flags();
        for (Map.Entry<String, FlagRule> entry : newFlags.entrySet()) {
            String auditKey = FLAG_KEY_PREFIX + entry.getKey();
            String oldRule = oldFlags.containsKey(entry.getKey()) ? summarize(oldFlags.get(entry.getKey())) : null;
            records.add(new ChangeRecord(auditKey, "FLAG",
                    ValueMasker.plain(oldRule), ValueMasker.plain(summarize(entry.getValue())),
                    now, operation));
        }
        for (String removed : oldFlags.keySet()) {
            if (!newFlags.containsKey(removed)) {
                records.add(new ChangeRecord(FLAG_KEY_PREFIX + removed, "FLAG",
                        ValueMasker.plain(summarize(oldFlags.get(removed))),
                        ValueMasker.plain(null), now, operation));
            }
        }
        return List.copyOf(records);
    }

    /** 规则摘要：只含百分比与名单大小，不含名单内的具体主体，避免审计记录泄露名单。 */
    private static String summarize(FlagRule rule) {
        return "percent=" + rule.percent()
                + ",allow=" + rule.allowList().size()
                + ",deny=" + rule.denyList().size()
                + ",forceOn=" + rule.forceOn().size()
                + ",forceOff=" + rule.forceOff().size();
    }
}
