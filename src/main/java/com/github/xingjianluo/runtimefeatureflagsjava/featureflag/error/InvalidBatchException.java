package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/** Raised when a publish/rollback batch fails validation; the whole batch is rejected atomically. */
public class InvalidBatchException extends FeatureFlagException {
    public InvalidBatchException(String message) {
        super(message);
    }
}
