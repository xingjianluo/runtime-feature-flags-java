package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.store;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.audit.ChangeRecord;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.audit.ValueRedactor;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.ConfigError;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.ConfigTypeMismatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidBatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidConfigValueException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.MissingConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionConflictException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionNotFoundException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.DecisionExplanation;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.DryRunReport;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Evaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.EvaluationStatus;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.FeatureFlag;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.KeyDiff;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishBatch;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishEntry;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.RolloutRule;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Version;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.VersionDiff;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.rollout.RolloutEvaluator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Versioned, concurrency-safe store of flags and runtime config values.
 *
 * <p>Consistency guarantees:
 * <ul>
 *   <li><b>Atomic publish</b>: a batch is fully validated against a copy of the current
 *       snapshot before anything changes; if any entry is invalid the batch is rejected
 *       with {@link InvalidBatchException} and no partial update is visible.</li>
 *   <li><b>Whole-version reads</b>: state is an immutable {@link Version} behind a single
 *       {@code volatile} reference, so a read observes exactly one complete version.</li>
 *   <li><b>Serialized writes</b>: publish/rollback take an exclusive lock, giving a total,
 *       monotonic version order even under concurrent publishers.</li>
 *   <li><b>Bounded history + rollback</b>: the last {@code historyLimit} versions are kept;
 *       rollback republishes a copy of an earlier snapshot as a brand-new version.</li>
 * </ul>
 */
public class FeatureFlagStore {

    private final int historyLimit;
    private final LongSupplier clock;
    private final RolloutEvaluator evaluator;
    private final LayeredConfigService configService;
    private final Object writeLock = new Object();

    private volatile Version current;
    // newest first; access guarded by writeLock for writes, snapshot copies for reads
    private final Deque<Version> history = new ArrayDeque<>();
    private final List<ChangeRecord> changeRecords = new ArrayList<>();

    public FeatureFlagStore(int historyLimit,
                            LongSupplier clock,
                            RolloutEvaluator evaluator,
                            LayeredConfigService configService) {
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be >= 1");
        }
        this.historyLimit = historyLimit;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.evaluator = evaluator;
        this.configService = configService;
        Version bootstrap = Version.bootstrap(this.clock.getAsLong(), Map.of(), Map.of());
        this.current = bootstrap;
        this.history.addFirst(bootstrap);
        if (configService != null) {
            configService.attachRuntimeView(() -> current.configValues());
        }
    }

    public Version current() {
        return current;
    }

    public long currentVersionNumber() {
        return current.versionNumber();
    }

    /**
     * Validate and atomically apply a batch, producing a new whole version.
     * Validation uses a private copy so the live version is untouched on failure.
     */
    public Version publish(PublishBatch batch) {
        return publish(batch, null);
    }

    /**
     * Optimistic-concurrency publish: when {@code expectedVersion} is non-null and no
     * longer matches the current version, the whole batch is rejected with
     * {@link VersionConflictException} before any validation or mutation. A null
     * {@code expectedVersion} keeps the legacy unconditional behavior exactly.
     */
    public Version publish(PublishBatch batch, Long expectedVersion) {
        if (batch == null) {
            throw new InvalidBatchException("publish batch must be non-null");
        }
        synchronized (writeLock) {
            checkExpectedVersion(expectedVersion);
            Version base = current;
            // Build candidate on private copies; never mutate the live snapshot.
            Map<String, FeatureFlag> flags = new HashMap<>(base.flags());
            Map<String, String> configValues = new HashMap<>(base.configValues());
            Map<String, String> origin = new HashMap<>(base.originVersion());
            java.util.Set<String> everKnown = new java.util.HashSet<>(base.everKnownKeys());

            List<ConfigErrorLite> errors = new ArrayList<>();
            long nextNumber = base.versionNumber() + 1;
            String nextVersion = String.valueOf(nextNumber);

            for (PublishEntry entry : batch.entries()) {
                if (entry.isFlagEntry()) {
                    FeatureFlag flag = entry.flag();
                    errors.addAll(validateFlag(flag));
                    flags.put(flag.key(), flag);
                    origin.put(flag.key(), nextVersion);
                    everKnown.add(flag.key());
                } else {
                    String key = entry.configKey();
                    if (configService == null) {
                        errors.add(new ConfigErrorLite(key, "no schema-backed config service available"));
                    } else {
                        try {
                            configService.validateValue(key, entry.value());
                        } catch (RuntimeException e) {
                            errors.add(new ConfigErrorLite(key, e.getMessage()));
                        }
                    }
                    configValues.put(key, entry.value());
                    origin.put(key, nextVersion);
                    everKnown.add(key);
                }
            }
            if (!errors.isEmpty()) {
                throw new InvalidBatchException("batch rejected with " + errors.size()
                        + " invalid entr(ies); no changes applied: " + summarize(errors));
            }

            Version next = new Version(nextNumber, flags, configValues,
                    clock.getAsLong(), batch.operator(), batch.reason(), origin, everKnown);
            commit(next);
            recordChanges(next, batch, "PUBLISH");
            return next;
        }
    }

    /**
     * Read-only preflight: validate a batch and preview its per-key effect against
     * the current version. Never mutates state, never advances the version.
     */
    public DryRunReport dryRun(PublishBatch batch) {
        if (batch == null) {
            throw new InvalidBatchException("dry-run batch must be non-null");
        }
        // Single volatile read of an immutable snapshot: no lock, no mutation,
        // concurrent readers and the live version are completely untouched.
        Version base = current;
        List<ConfigError> errors = new ArrayList<>();
        List<KeyDiff> changes = new ArrayList<>();
        for (PublishEntry entry : batch.entries()) {
            if (entry.isFlagEntry()) {
                FeatureFlag flag = entry.flag();
                errors.addAll(validateFlagTyped(flag));
                FeatureFlag before = base.flags().get(flag.key());
                String beforeSummary = before == null ? "absent" : flagFingerprint(before);
                String afterSummary = flagFingerprint(flag);
                KeyDiff.Kind kind = before == null
                        ? KeyDiff.Kind.ADDED
                        : beforeSummary.equals(afterSummary) ? KeyDiff.Kind.UNCHANGED : KeyDiff.Kind.CHANGED;
                changes.add(new KeyDiff(flag.key(), kind, beforeSummary, afterSummary));
            } else {
                String key = entry.configKey();
                errors.addAll(validateConfigTyped(key, entry.value()));
                String beforeRaw = base.configValues().get(key);
                String beforeSummary = ValueRedactor.redact(beforeRaw);
                String afterSummary = ValueRedactor.redact(entry.value());
                KeyDiff.Kind kind = beforeRaw == null
                        ? KeyDiff.Kind.ADDED
                        : beforeSummary.equals(afterSummary) ? KeyDiff.Kind.UNCHANGED : KeyDiff.Kind.CHANGED;
                changes.add(new KeyDiff(key, kind, beforeSummary, afterSummary));
            }
        }
        return new DryRunReport(base.versionNumber(), errors.isEmpty(), errors, changes);
    }

    /**
     * Key-level diff between two retained versions; redacted summaries only.
     * Both versions must still be in the retained history, otherwise a
     * {@link VersionNotFoundException} distinguishes the expired/unknown version.
     */
    public VersionDiff diff(long fromVersion, long toVersion) {
        synchronized (writeLock) {
            Version from = findVersion(fromVersion)
                    .orElseThrow(() -> new VersionNotFoundException(
                            "version " + fromVersion + " not found in retained history"));
            Version to = findVersion(toVersion)
                    .orElseThrow(() -> new VersionNotFoundException(
                            "version " + toVersion + " not found in retained history"));
            List<KeyDiff> changes = new ArrayList<>();
            diffMaps(flagSummaries(from.flags()), flagSummaries(to.flags()), changes);
            diffMaps(redactValues(from.configValues()), redactValues(to.configValues()), changes);
            changes.sort(java.util.Comparator.comparing(KeyDiff::key));
            return new VersionDiff(fromVersion, toVersion, changes);
        }
    }

    /**
     * Roll the whole state back to an exact earlier version by publishing a copy of it.
     * The current (and intermediate) versions remain in history; the rollback is a new
     * version, so the operation itself is auditable and can itself be rolled back.
     */
    public Version rollbackTo(long targetVersion, String operator, String reason) {
        return rollbackTo(targetVersion, operator, reason, null);
    }

    /**
     * Optimistic-concurrency rollback: when {@code expectedVersion} is non-null and no
     * longer matches the current version, the rollback is rejected with
     * {@link VersionConflictException}. Null keeps the legacy behavior exactly.
     */
    public Version rollbackTo(long targetVersion, String operator, String reason, Long expectedVersion) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("rollback operator must be non-blank");
        }
        synchronized (writeLock) {
            checkExpectedVersion(expectedVersion);
            Version target = findVersion(targetVersion)
                    .orElseThrow(() -> new VersionNotFoundException(
                            "version " + targetVersion + " not found in retained history"));
            Version base = current;
            long nextNumber = base.versionNumber() + 1;
            String rollbackReason = "rollback to v" + targetVersion
                    + (reason == null || reason.isBlank() ? "" : ": " + reason);
            // Republish the target's complete flags/config under a new number; origins preserved.
            // Retain the union of ever-known keys so a key removed by this rollback is reported
            // as ROLLED_BACK rather than UNKNOWN_FLAG.
            java.util.Set<String> everKnown = new java.util.HashSet<>(base.everKnownKeys());
            everKnown.addAll(target.everKnownKeys());
            Version next = new Version(nextNumber,
                    new HashMap<>(target.flags()),
                    new HashMap<>(target.configValues()),
                    clock.getAsLong(),
                    operator,
                    rollbackReason,
                    new HashMap<>(target.originVersion()),
                    everKnown);
            commit(next);
            recordRollback(next, target, base);
            return next;
        }
    }

    public Optional<Version> version(long versionNumber) {
        synchronized (writeLock) {
            return findVersion(versionNumber);
        }
    }

    public List<Version> history() {
        synchronized (writeLock) {
            return List.copyOf(history);
        }
    }

    /**
     * Evaluate a flag for a subject against the current whole version.
     * Status distinguishes unknown / disabled / rolled-back / enabled.
     */
    public Evaluation evaluate(String flagKey, String subject) {
        Version snapshot = current; // single volatile read: this call observes one version
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey must be non-blank");
        }
        if (subject == null) {
            throw new IllegalArgumentException("subject must be non-null");
        }

        FeatureFlag flag = snapshot.flags().get(flagKey);
        if (flag == null) {
            // Absent now: distinguish "seen before and removed (rolled back)" from "never existed".
            EvaluationStatus status = snapshot.everKnownKeys().contains(flagKey)
                    ? EvaluationStatus.ROLLED_BACK
                    : EvaluationStatus.UNKNOWN_FLAG;
            return new Evaluation(flagKey, subject, false, status,
                    snapshot.versionNumber(),
                    DecisionExplanation.of("not-present-in-version", flagKey, subject));
        }
        if (!flag.enabled()) {
            return new Evaluation(flagKey, subject, false, EvaluationStatus.DISABLED,
                    snapshot.versionNumber(),
                    DecisionExplanation.of("flag-disabled", flagKey, subject));
        }
        RolloutEvaluator.Decision decision = evaluator.evaluate(flag, subject);
        EvaluationStatus status = decision.selected()
                ? EvaluationStatus.ENABLED
                : EvaluationStatus.DISABLED;
        return new Evaluation(flagKey, subject, decision.selected(), status,
                snapshot.versionNumber(), decision.explanation());
    }

    public List<ChangeRecord> changeRecords() {
        synchronized (writeLock) {
            return List.copyOf(changeRecords);
        }
    }

    public List<ChangeRecord> changeRecordsFor(String key) {
        synchronized (writeLock) {
            return changeRecords.stream().filter(r -> r.key().equals(key)).toList();
        }
    }

    // ---- internals (all called under writeLock unless noted) ----

    /**
     * Reject the operation when the caller pinned a base version that has since moved.
     * Runs inside the write lock, before any validation or mutation, so a stale
     * commit can never partially apply or overwrite someone else's publish.
     */
    private void checkExpectedVersion(Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != current.versionNumber()) {
            throw new VersionConflictException("expected base version " + expectedVersion
                    + " but current version is " + current.versionNumber()
                    + "; the batch was rejected without applying anything");
        }
    }

    private void commit(Version next) {
        current = next;
        history.addFirst(next);
        while (history.size() > historyLimit) {
            history.removeLast();
        }
    }

    private Optional<Version> findVersion(long number) {
        return history.stream().filter(v -> v.versionNumber() == number).findFirst();
    }

    private List<ConfigErrorLite> validateFlag(FeatureFlag flag) {
        List<ConfigErrorLite> errors = new ArrayList<>();
        if (flag == null) {
            errors.add(new ConfigErrorLite("?", "flag entry is null"));
            return errors;
        }
        RolloutRule rule = flag.rule();
        if (rule.percentage() < 0 || rule.percentage() > 100) {
            errors.add(new ConfigErrorLite(flag.key(), "percentage out of [0,100]"));
        }
        if (rule.forceEnabled() && rule.forceDisabled()) {
            errors.add(new ConfigErrorLite(flag.key(), "forceEnabled and forceDisabled both set"));
        }
        return errors;
    }

    /** Typed variant of {@link #validateFlag} for dry-run reports. */
    private List<ConfigError> validateFlagTyped(FeatureFlag flag) {
        return validateFlag(flag).stream()
                .map(e -> new ConfigError(e.key(), ConfigError.Type.INVALID_VALUE, e.detail()))
                .toList();
    }

    /**
     * Validate a config entry and classify the failure so a dry-run report can
     * distinguish unknown keys, missing required values, type mismatches and
     * constraint violations instead of collapsing them into one opaque failure.
     */
    private List<ConfigError> validateConfigTyped(String key, String raw) {
        if (configService == null) {
            return List.of(new ConfigError(key, ConfigError.Type.UNKNOWN_KEY,
                    "no schema-backed config service available"));
        }
        if (!configService.isKnown(key)) {
            return List.of(new ConfigError(key, ConfigError.Type.UNKNOWN_KEY,
                    "unknown config key '" + key + "'; not declared in schema"));
        }
        try {
            configService.validateValue(key, raw);
            return List.of();
        } catch (MissingConfigException e) {
            return List.of(new ConfigError(key, ConfigError.Type.MISSING, e.getMessage()));
        } catch (ConfigTypeMismatchException e) {
            return List.of(new ConfigError(key, ConfigError.Type.TYPE_MISMATCH, e.getMessage()));
        } catch (InvalidConfigValueException e) {
            return List.of(new ConfigError(key, ConfigError.Type.INVALID_VALUE, e.getMessage()));
        }
    }

    private void recordChanges(Version next, PublishBatch batch, String kind) {
        for (PublishEntry entry : batch.entries()) {
            String key = entry.entryKey();
            String redacted = entry.isConfigEntry()
                    ? ValueRedactor.redact(entry.value())
                    : ValueRedactor.redact(flagFingerprint(entry.flag()));
            changeRecords.add(new ChangeRecord(next.versionNumber(), key,
                    entry.isFlagEntry() ? "UPSERT_FLAG" : "UPSERT_CONFIG",
                    redacted, batch.operator(), batch.reason(), next.publishedAt()));
        }
    }

    private void recordRollback(Version next, Version target, Version base) {
        // Record each key present before the rollback but absent in the restored snapshot;
        // one record per removed flag/config key, plus a single ROLLBACK marker. No raw values.
        java.util.List<String> removedKeys = new ArrayList<>();
        base.flags().keySet().stream()
                .filter(k -> !next.flags().containsKey(k))
                .forEach(removedKeys::add);
        base.configValues().keySet().stream()
                .filter(k -> !next.configValues().containsKey(k))
                .forEach(removedKeys::add);
        for (String removedKey : removedKeys) {
            changeRecords.add(new ChangeRecord(next.versionNumber(), removedKey,
                    "REMOVED_BY_ROLLBACK", "absent",
                    next.operator(), next.reason(), next.publishedAt()));
        }
        changeRecords.add(new ChangeRecord(next.versionNumber(), "*rollback*",
                "ROLLBACK", "target=v" + target.versionNumber(),
                next.operator(), next.reason(), next.publishedAt()));
    }

    private static String flagFingerprint(FeatureFlag flag) {
        // Structural fingerprint, never secret payload values.
        return flag.key() + "|enabled=" + flag.enabled()
                + "|pct=" + flag.rule().percentage()
                + "|allow=" + flag.rule().allowSubjects().size()
                + "|deny=" + flag.rule().denySubjects().size()
                + "|forceOn=" + flag.rule().forceEnabled()
                + "|forceOff=" + flag.rule().forceDisabled();
    }

    /** Structural fingerprints for every flag (no secret payload values). */
    private static Map<String, String> flagSummaries(Map<String, FeatureFlag> flags) {
        Map<String, String> out = new HashMap<>();
        flags.forEach((key, flag) -> out.put(key, flagFingerprint(flag)));
        return out;
    }

    /** Redacted digest for every raw config value. */
    private static Map<String, String> redactValues(Map<String, String> values) {
        Map<String, String> out = new HashMap<>();
        values.forEach((key, raw) -> out.put(key, ValueRedactor.redact(raw)));
        return out;
    }

    /** Diff two redacted summary maps, appending only keys that differ. */
    private static void diffMaps(Map<String, String> from, Map<String, String> to,
                                 List<KeyDiff> out) {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(from.keySet());
        keys.addAll(to.keySet());
        for (String key : keys) {
            String before = from.get(key);
            String after = to.get(key);
            if (before == null) {
                out.add(new KeyDiff(key, KeyDiff.Kind.ADDED, "absent", after));
            } else if (after == null) {
                out.add(new KeyDiff(key, KeyDiff.Kind.REMOVED, before, "absent"));
            } else if (!before.equals(after)) {
                out.add(new KeyDiff(key, KeyDiff.Kind.CHANGED, before, after));
            }
        }
    }

    private static String summarize(List<ConfigErrorLite> errors) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(errors.size(), 10);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append('[').append(errors.get(i).key()).append("] ").append(errors.get(i).detail());
        }
        if (errors.size() > shown) {
            sb.append("; ... and ").append(errors.size() - shown).append(" more");
        }
        return sb.toString();
    }

    private record ConfigErrorLite(String key, String detail) {
    }
}
