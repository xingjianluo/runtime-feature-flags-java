package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/** Raised when a required config key has no value in any layer and no default. */
public class MissingConfigException extends FeatureFlagException {
    public MissingConfigException(String message) {
        super(message);
    }
}
