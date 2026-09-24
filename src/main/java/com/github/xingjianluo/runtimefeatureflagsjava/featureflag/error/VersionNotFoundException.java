package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/** Raised when a requested version does not exist or has already expired from history. */
public class VersionNotFoundException extends FeatureFlagException {
    public VersionNotFoundException(String message) {
        super(message);
    }
}
