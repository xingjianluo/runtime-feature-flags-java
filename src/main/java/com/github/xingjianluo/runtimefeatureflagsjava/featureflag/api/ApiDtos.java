package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.api;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.audit.ChangeRecord;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.DryRunReport;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Evaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.KeyDiff;
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

    public record PublishRequest(List<FlagDto> flags,
                                 List<ConfigEntryDto> config,
                                 String operator,
                                 String reason,
                                 Long expectedVersion) {
    }

    public record RollbackRequest(Long targetVersion, String operator, String reason,
                                  Long expectedVersion) {
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

    /** One categorized validation failure inside a dry-run report. */
    public record DryRunErrorDto(String key, String type, String detail) {
    }

    /** Per-key effect with redacted before/after summaries only. */
    public record KeyDiffDto(String key, String kind, String beforeSummary, String afterSummary) {
        static KeyDiffDto from(KeyDiff d) {
            return new KeyDiffDto(d.key(), d.kind().name(), d.beforeSummary(), d.afterSummary());
        }
    }

    /** Read-only preflight result: categorized errors + per-key preview + base version. */
    public record DryRunReportDto(long baseVersion, boolean valid,
                                  List<DryRunErrorDto> errors, List<KeyDiffDto> changes) {
        static DryRunReportDto from(DryRunReport r) {
            return new DryRunReportDto(r.baseVersion(), r.valid(),
                    r.errors().stream()
                            .map(e -> new DryRunErrorDto(e.key(), e.type().name(), e.detail()))
                            .toList(),
                    r.changes().stream().map(KeyDiffDto::from).toList());
        }
    }

    /** Key-level diff between two retained versions; redacted summaries only. */
    public record VersionDiffDto(long fromVersion, long toVersion, List<KeyDiffDto> changes) {
        static VersionDiffDto from(VersionDiff d) {
            return new VersionDiffDto(d.fromVersion(), d.toVersion(),
                    d.changes().stream().map(KeyDiffDto::from).toList());
        }
    }

    public record ErrorDto(String error, String type, String message) {
    }
}
