package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.api;

import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.ConfigTypeMismatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.FeatureFlagException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidBatchException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.InvalidConfigValueException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.MissingConfigException;
import com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error.VersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates typed domain errors into distinguishable HTTP responses.
 * Each {@code type} token lets clients branch on the failure kind reliably.
 */
@RestControllerAdvice
public class FeatureFlagExceptionAdvice {

    @ExceptionHandler(InvalidBatchException.class)
    public ResponseEntity<ApiDtos.ErrorDto> invalidBatch(InvalidBatchException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BATCH", e.getMessage());
    }

    @ExceptionHandler(MissingConfigException.class)
    public ResponseEntity<ApiDtos.ErrorDto> missing(MissingConfigException e) {
        return build(HttpStatus.NOT_FOUND, "MISSING_CONFIG", e.getMessage());
    }

    @ExceptionHandler(ConfigTypeMismatchException.class)
    public ResponseEntity<ApiDtos.ErrorDto> typeMismatch(ConfigTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH", e.getMessage());
    }

    @ExceptionHandler(InvalidConfigValueException.class)
    public ResponseEntity<ApiDtos.ErrorDto> invalidValue(InvalidConfigValueException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_VALUE", e.getMessage());
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<ApiDtos.ErrorDto> versionNotFound(VersionNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiDtos.ErrorDto> illegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiDtos.ErrorDto> unreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "request body is missing or malformed");
    }

    @ExceptionHandler(FeatureFlagException.class)
    public ResponseEntity<ApiDtos.ErrorDto> generic(FeatureFlagException e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "FEATURE_FLAG_ERROR", e.getMessage());
    }

    private ResponseEntity<ApiDtos.ErrorDto> build(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status)
                .body(new ApiDtos.ErrorDto("feature_flag_error", type, message));
    }
}
