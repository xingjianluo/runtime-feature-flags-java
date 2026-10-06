package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.api;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.audit.ChangeRecord;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Evaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.KeyChange;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PreflightIssue;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PreflightReport;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Version;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.VersionDiff;

import java.util.List;

/** Request/response payloads for the feature flag REST API. Raw secret values are never returned. */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** One entry in a publish request. Exactly one of flag/config is present. */
    public record FlagDto(String key, Boolean enabled, Integer percentage,
                          List<String> allow, List<String> deny,
                          Boolean forceEnabled, Boolean forceDisabled) {
    }

    public record ConfigEntryDto(String key, String value) {
    }

    /**
     * @param expectedBaseVersion optional optimistic-concurrency pin: when present and no
     *                            longer the current version, the batch is rejected with
     *                            a distinguishable VERSION_CONFLICT; absent keeps legacy behavior
     */
    public record PublishRequest(List<FlagDto> flags,
                                 List<ConfigEntryDto> config,
                                 String operator,
                                 String reason,
                                 Long expectedBaseVersion) {
    }

    public record RollbackRequest(Long targetVersion, String operator, String reason,
                                  Long expectedBaseVersion) {
    }

    public record ExplanationDto(String reason, String flagKey, Integer bucket, Integer percentage) {
    }

    public record EvaluationDto(String flagKey, String subject, boolean enabled,
                                String status, long version, ExplanationDto explanation) {
        static EvaluationDto from(Evaluation e) {
            var x = e.explanation();
            return new EvaluationDto(e.flagKey(), e.subject(), e.enabled(), e.status().name(),
                    e.observedVersion(),
                    new ExplanationDto(x.reason(), x.flagKey(),
                            x.bucket() < 0 ? null : x.bucket(),
                            x.percentage() < 0 ? null : x.percentage()));
        }
    }

    /** Redacted version view: only keys, not values (values may be secret). */
    public record VersionSummaryDto(long version, String operator, String reason,
                                    long publishedAt, List<String> flagKeys,
                                    List<String> configKeys) {
        static VersionSummaryDto from(Version v) {
            return new VersionSummaryDto(v.versionNumber(), v.operator(), v.reason(),
                    v.publishedAt(),
                    v.flags().keySet().stream().sorted().toList(),
                    v.configValues().keySet().stream().sorted().toList());
        }
    }

    public record ChangeRecordDto(long version, String key, String kind,
                                  String redactedValue, String operator,
                                  String reason, long at) {
        static ChangeRecordDto from(ChangeRecord r) {
            return new ChangeRecordDto(r.versionNumber(), r.key(), r.kind(),
                    r.redactedValue(), r.operator(), r.reason(), r.at());
        }
    }

    public record PublishResultDto(long version, List<VersionSummaryDto> history) {
    }

    public record ConfigSnapshotDto(long version, java.util.Map<String, Object> values) {
    }

    /** One categorized preflight issue. */
    public record PreflightIssueDto(String key, String category, String detail) {
        static PreflightIssueDto from(PreflightIssue issue) {
            return new PreflightIssueDto(issue.key(), issue.category().name(), issue.detail());
        }
    }

    /** Per-key change with redacted digests only. */
    public record KeyChangeDto(String key, String kind, String beforeDigest, String afterDigest) {
        static KeyChangeDto from(KeyChange change) {
            return new KeyChangeDto(change.key(), change.kind().name(),
                    change.beforeDigest(), change.afterDigest());
        }
    }

    /** Read-only preflight outcome: validity, categorized issues, per-key preview. */
    public record PreflightReportDto(long baseVersion, boolean valid,
                                     List<PreflightIssueDto> issues,
                                     List<KeyChangeDto> changes) {
        public static PreflightReportDto from(PreflightReport report) {
            return new PreflightReportDto(report.baseVersion(), report.valid(),
                    report.issues().stream().map(PreflightIssueDto::from).toList(),
                    report.changes().stream().map(KeyChangeDto::from).toList());
        }
    }

    /** Redacted diff between two retained versions. */
    public record VersionDiffDto(long fromVersion, long toVersion, List<KeyChangeDto> entries) {
        public static VersionDiffDto from(VersionDiff diff) {
            return new VersionDiffDto(diff.fromVersion(), diff.toVersion(),
                    diff.entries().stream().map(KeyChangeDto::from).toList());
        }
    }

    public record ErrorDto(String error, String type, String message) {
    }
}
