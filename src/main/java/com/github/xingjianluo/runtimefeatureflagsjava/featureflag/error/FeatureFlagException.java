package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/** Base class for all feature flag / config errors so callers can distinguish failure kinds by subtype. */
public abstract class FeatureFlagException extends RuntimeException {
    protected FeatureFlagException(String message) {
        super(message);
    }
}
