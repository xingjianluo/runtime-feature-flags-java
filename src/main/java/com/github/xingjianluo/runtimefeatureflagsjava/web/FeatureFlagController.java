package com.github.xingjianluo.runtimefeatureflagsjava.web;

import com.github.xingjianluo.runtimefeatureflagsjava.dto.PublishRequest;
import com.github.xingjianluo.runtimefeatureflagsjava.dto.RollbackRequest;
import com.github.xingjianluo.runtimefeatureflagsjava.model.FlagEvaluation;
import com.github.xingjianluo.runtimefeatureflagsjava.service.FeatureFlagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 发布 / 回滚 / 灰度判定 / 配置查询 / 变更记录的 HTTP 入口。
 * 所有写操作都是整批原子语义，校验失败返回 422 且线上版本不变。
 */
@RestController
@RequestMapping("/api")
public class FeatureFlagController {
    private final FeatureFlagService service;

    public FeatureFlagController(FeatureFlagService service) {
        this.service = service;
    }

    /** 整批发布：configs 与 flags 同批原子生效。 */
    @PostMapping("/feature-flags/publish")
    public Map<String, Object> publish(@RequestBody PublishRequest request) {
        long version = service.publishBatch(request.configs(), request.flags(),
                request.publishedBy(), request.note());
        return Map.of("version", version, "committed", true);
    }

    /** 回滚到历史版本（生成新版本号）。 */
    @PostMapping("/feature-flags/rollback")
    public Map<String, Object> rollback(@RequestBody RollbackRequest request) {
        long version = service.rollback(request.targetVersion(), request.publishedBy());
        return Map.of("version", version, "restoredFrom", request.targetVersion());
    }

    /** 灰度判定：返回四态结果与判定依据。 */
    @GetMapping("/feature-flags/evaluate")
    public FlagEvaluation evaluate(@RequestParam String flag, @RequestParam String subject) {
        return service.evaluate(flag, subject);
    }

    /** 读取分层合并后的配置值及命中层。 */
    @GetMapping("/configs/{key}")
    public Map<String, Object> getConfig(@PathVariable String key) {
        var resolved = service.resolvedConfig(key);
        return Map.of("key", resolved.key(), "value", resolved.rawValue(),
                "layer", resolved.layer().name(), "layerPrecedence", resolved.layer().precedence());
    }

    /** 查询某键（配置或开关）变更记录；敏感值已脱敏。 */
    @GetMapping("/feature-flags/history")
    public Map<String, Object> history(@RequestParam String key) {
        return Map.of("key", key, "records", service.changeHistory(key));
    }

    /** 当前生效版本号。 */
    @GetMapping("/feature-flags/version")
    public Map<String, Object> version() {
        return Map.of("version", service.currentVersion());
    }
}
