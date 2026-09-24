package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.api;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.config.LayeredConfigService;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidBatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.FeatureFlag;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishBatch;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.PublishEntry;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.RolloutRule;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.model.Version;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.store.FeatureFlagStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** REST surface for publish / rollback / evaluate / history / changes / config. */
@RestController
@RequestMapping("/api/feature-flags")
public class FeatureFlagController {

    private final FeatureFlagStore store;
    private final LayeredConfigService configService;

    public FeatureFlagController(FeatureFlagStore store, LayeredConfigService configService) {
        this.store = store;
        this.configService = configService;
    }

    /** Evaluate a flag for a subject; status distinguishes enabled/disabled/unknown/rolled-back. */
    @GetMapping("/evaluate")
    public ApiDtos.EvaluationDto evaluate(@RequestParam String key, @RequestParam String subject) {
        return ApiDtos.EvaluationDto.from(store.evaluate(key, subject));
    }

    /** Redacted version history (keys only, never values). */
    @GetMapping("/history")
    public List<ApiDtos.VersionSummaryDto> history() {
        return store.history().stream().map(ApiDtos.VersionSummaryDto::from).toList();
    }

    /** Query change records, optionally filtered by key; values are redacted digests only. */
    @GetMapping("/changes")
    public List<ApiDtos.ChangeRecordDto> changes(@RequestParam(required = false) String key) {
        var records = (key == null || key.isBlank())
                ? store.changeRecords()
                : store.changeRecordsFor(key);
        return records.stream().map(ApiDtos.ChangeRecordDto::from).toList();
    }

    /** Fully merged, typed config snapshot with the version it was observed at. */
    @GetMapping("/config")
    public ApiDtos.ConfigSnapshotDto config() {
        return new ApiDtos.ConfigSnapshotDto(store.currentVersionNumber(), configService.snapshot());
    }

    /** Atomically publish a batch of flags/config; either all entries apply or none do. */
    @PostMapping("/publish")
    public ApiDtos.PublishResultDto publish(@RequestBody ApiDtos.PublishRequest request) {
        PublishBatch batch = toBatch(request);
        Version published = store.publish(batch);
        return new ApiDtos.PublishResultDto(published.versionNumber(),
                store.history().stream().map(ApiDtos.VersionSummaryDto::from).toList());
    }

    /** Atomically roll the whole state back to a retained version. */
    @PostMapping("/rollback")
    public ApiDtos.PublishResultDto rollback(@RequestBody ApiDtos.RollbackRequest request) {
        if (request == null || request.targetVersion() == null) {
            throw new InvalidBatchException("targetVersion is required for rollback");
        }
        String operator = request.operator();
        if (operator == null || operator.isBlank()) {
            throw new InvalidBatchException("operator is required for rollback");
        }
        Version rolled = store.rollbackTo(request.targetVersion(), operator, request.reason());
        return new ApiDtos.PublishResultDto(rolled.versionNumber(),
                store.history().stream().map(ApiDtos.VersionSummaryDto::from).toList());
    }

    private PublishBatch toBatch(ApiDtos.PublishRequest request) {
        if (request == null) {
            throw new InvalidBatchException("publish body is required");
        }
        if (request.operator() == null || request.operator().isBlank()) {
            throw new InvalidBatchException("operator is required for publish");
        }
        List<PublishEntry> entries = new ArrayList<>();
        if (request.flags() != null) {
            for (ApiDtos.FlagDto flag : request.flags()) {
                entries.add(PublishEntry.flag(toFlag(flag)));
            }
        }
        if (request.config() != null) {
            for (ApiDtos.ConfigEntryDto entry : request.config()) {
                if (entry == null || entry.key() == null || entry.key().isBlank()) {
                    throw new InvalidBatchException("config entry requires a non-blank key");
                }
                entries.add(PublishEntry.config(entry.key(), entry.value()));
            }
        }
        if (entries.isEmpty()) {
            throw new InvalidBatchException("batch must contain at least one flag or config entry");
        }
        return new PublishBatch(entries, request.operator(),
                request.reason() == null ? "" : request.reason());
    }

    private FeatureFlag toFlag(ApiDtos.FlagDto dto) {
        if (dto == null || dto.key() == null || dto.key().isBlank()) {
            throw new InvalidBatchException("flag entry requires a non-blank key");
        }
        int percentage = dto.percentage() == null ? 0 : dto.percentage();
        boolean enabled = !Boolean.FALSE.equals(dto.enabled());
        RolloutRule rule = new RolloutRule(percentage,
                dto.allow() == null ? java.util.Set.of() : java.util.Set.copyOf(dto.allow()),
                dto.deny() == null ? java.util.Set.of() : java.util.Set.copyOf(dto.deny()),
                Boolean.TRUE.equals(dto.forceEnabled()),
                Boolean.TRUE.equals(dto.forceDisabled()));
        return new FeatureFlag(dto.key(), enabled, rule, java.util.List.of());
    }
}
