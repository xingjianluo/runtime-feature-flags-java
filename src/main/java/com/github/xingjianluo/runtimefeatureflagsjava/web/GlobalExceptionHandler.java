package com.github.xingjianluo.runtimefeatureflagsjava.web;

import com.github.xingjianluo.runtimefeatureflagsjava.error.BatchValidationException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.ConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.InvalidValueConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.MissingConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.TypeMismatchConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.error.UnknownConfigKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 将可区分错误类型映射为可区分的 HTTP 状态码与错误码：
 * <ul>
 *   <li>{@link BatchValidationException} → 422 UNPROCESSABLE_ENTITY / BATCH_INVALID（整批拒绝，未生效）</li>
 *   <li>{@link UnknownConfigKeyException} → 404 / UNKNOWN_KEY</li>
 *   <li>{@link MissingConfigException} → 409 / MISSING（注册表已知但三层无值）</li>
 *   <li>{@link TypeMismatchConfigException} → 422 / TYPE_MISMATCH</li>
 *   <li>{@link InvalidValueConfigException} → 422 / INVALID_VALUE（含非法回滚目标）</li>
 *   <li>{@link IllegalArgumentException} → 400 / BAD_REQUEST</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BatchValidationException.class)
    public ResponseEntity<Map<String, Object>> handleBatch(BatchValidationException e) {
        List<String> details = e.getErrors().stream().map(Throwable::getMessage).toList();
        log.warn("批次校验失败，已整批拒绝: {}", details);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "errorCode", "BATCH_INVALID",
                "message", e.getMessage(),
                "details", details,
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(UnknownConfigKeyException.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(UnknownConfigKeyException e) {
        return error(HttpStatus.NOT_FOUND, "UNKNOWN_KEY", e);
    }

    @ExceptionHandler(MissingConfigException.class)
    public ResponseEntity<Map<String, Object>> handleMissing(MissingConfigException e) {
        return error(HttpStatus.CONFLICT, "MISSING", e);
    }

    @ExceptionHandler(TypeMismatchConfigException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(TypeMismatchConfigException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "TYPE_MISMATCH", e);
    }

    @ExceptionHandler(InvalidValueConfigException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(InvalidValueConfigException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VALUE", e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, Exception e) {
        log.warn("配置错误 {}: {}", code, e.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "errorCode", code,
                "message", String.valueOf(e.getMessage()),
                "timestamp", Instant.now().toString()));
    }
}
