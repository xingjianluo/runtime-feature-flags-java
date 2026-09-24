package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/** Raised when a configured value cannot be converted to the declared type. */
public class ConfigTypeMismatchException extends FeatureFlagException {
    public ConfigTypeMismatchException(String message) {
        super(message);
    }
}
