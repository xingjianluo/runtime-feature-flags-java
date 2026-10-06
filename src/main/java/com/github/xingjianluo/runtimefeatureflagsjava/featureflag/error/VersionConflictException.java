package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/**
 * The caller pinned a publish/rollback to an expected base version, but the current
 * version has already moved past it (someone else committed in between). Distinct from
 * {@link InvalidBatchException}: the batch itself may be valid, it is simply stale.
 * The whole batch is rejected; nothing is applied, nothing is overwritten.
 */
public class VersionConflictException extends FeatureFlagException {
    public VersionConflictException(String message) {
        super(message);
    }
}
