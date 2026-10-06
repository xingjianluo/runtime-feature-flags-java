package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/**
 * Raised when a publish/rollback declared an expected base version that no longer
 * matches the current version (someone else published in between). Distinguishable
 * from validation failures: nothing is applied and no version is advanced.
 */
public class VersionConflictException extends FeatureFlagException {
    public VersionConflictException(String message) {
        super(message);
    }
}
