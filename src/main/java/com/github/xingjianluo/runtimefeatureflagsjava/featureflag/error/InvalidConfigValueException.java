package com.github.xingjianluo.runtimefeatureflagsjava.featureflag.error;

/** Raised when a value has the right type but violates declared constraints (range/enum/regex). */
public class InvalidConfigValueException extends FeatureFlagException {
    public InvalidConfigValueException(String message) {
        super(message);
    }
}
